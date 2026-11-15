package com.example.durakgame.service.store;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, store-only operation receipts; timestamps are creation times in epoch milliseconds. */
final class WriteReceipts {
    static final long RETENTION_MS = Duration.ofMinutes(10).toMillis();
    static final int MAX_RECEIPTS = 1024;

    private WriteReceipts() {
    }

    static void requireId(String receiptId) {
        if (receiptId == null || receiptId.isBlank()) {
            throw new IllegalArgumentException("A write receipt ID is required");
        }
    }

    /** Called only for a new committed write, never to refresh a replayed receipt. */
    static Map<String, Long> merge(Map<String, Long> existing, String receiptId, long now) {
        Map<String, Long> receipts = new HashMap<>(existing);
        receipts.entrySet().removeIf(entry -> entry.getValue() <= now - RETENTION_MS);
        if (receiptId != null) {
            receipts.putIfAbsent(receiptId, now);
        }
        Map<String, Long> newest = new LinkedHashMap<>();
        receipts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        // The just-created receipt is newest when several writes share a millisecond.
                        .thenComparing(entry -> !entry.getKey().equals(receiptId))
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_RECEIPTS)
                .forEach(entry -> newest.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(newest);
    }
}
