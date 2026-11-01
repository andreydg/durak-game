package com.example.durakgame.service.autoplay;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * One monotonic time budget for an outbound call: waiting for a credential, the response headers
 * and the complete body all count against it (the JDK's request timeout alone stops at the headers).
 * Ported from Glass Bead's Anthropic client.
 */
final class OutboundDeadline {
    /** What happens to a pending future whose wait ran out or was interrupted. */
    enum Abandon {
        /** Cancel it, interrupting an exchange in flight. */
        CANCEL_EXCHANGE,
        /** Leave it running for other waiters (a shared token exchange). */
        LEAVE_RUNNING;

        void apply(Future<?> pending) {
            if (this == CANCEL_EXCHANGE) {
                pending.cancel(true);
            }
        }
    }

    private final long endsAt;
    private final LongSupplier nanoTime;

    private OutboundDeadline(long endsAt, LongSupplier nanoTime) {
        this.endsAt = endsAt;
        this.nanoTime = nanoTime;
    }

    static OutboundDeadline after(Duration timeout, LongSupplier nanoTime) {
        return new OutboundDeadline(nanoTime.getAsLong() + timeout.toNanos(), nanoTime);
    }

    /** The time left, as a request timeout; throws once none is left. */
    Duration requestTimeout() throws HttpTimeoutException {
        long remaining = endsAt - nanoTime.getAsLong();
        if (remaining <= 0) {
            throw new HttpTimeoutException("The outbound call's time ran out");
        }
        return Duration.ofNanos(remaining);
    }

    /** Waits for {@code pending} within the time left; an {@link IOException} cause is rethrown as is. */
    <T> T await(Future<T> pending, Abandon abandon) throws IOException, InterruptedException {
        long remaining = Math.max(0, endsAt - nanoTime.getAsLong());
        try {
            return pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            abandon.apply(pending);
            throw new HttpTimeoutException("The outbound call's time ran out before completion");
        } catch (InterruptedException interrupted) {
            abandon.apply(pending);
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("Outbound call failed");
        }
    }

    /** Sends {@code request} and waits for the complete response within the time left. */
    <T> HttpResponse<T> send(HttpClient client, HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> pending = client.sendAsync(request.timeout(requestTimeout()).build(),
                handler);
        return await(pending, Abandon.CANCEL_EXCHANGE);
    }

    /**
     * Reads at most {@code max + 1} bytes of a wanted response (so callers can tell an oversized body)
     * and none of an unwanted one, which is never kept.
     */
    static HttpResponse.BodyHandler<byte[]> firstBytes(int max, Predicate<HttpResponse.ResponseInfo> wanted) {
        if (max < 0 || max == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid body limit");
        }
        return response -> new FirstBytes(wanted.test(response) ? max + 1 : 0);
    }

    private static final class FirstBytes implements HttpResponse.BodySubscriber<byte[]> {
        private final byte[] bytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int count;

        FirstBytes(int capacity) {
            bytes = new byte[capacity];
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription offered) {
            if (subscription != null) {
                offered.cancel();
                return;
            }
            subscription = offered;
            if (bytes.length == 0) {
                result.complete(bytes);
                offered.cancel();
            } else {
                offered.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int take = Math.min(buffer.remaining(), bytes.length - count);
                buffer.get(bytes, count, take);
                count += take;
                if (count == bytes.length) {
                    result.complete(bytes);
                    subscription.cancel();
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(Arrays.copyOf(bytes, count));
        }
    }
}
