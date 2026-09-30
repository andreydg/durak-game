package com.example.durakgame.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * One JSON object per log event in the shape Google Cloud Logging understands: {@code severity}
 * becomes the entry's level (so WARNING/ERROR filtering and alerting work), {@code message} is the
 * display text, and {@code time} is the event timestamp. A stack trace stays inside its entry
 * instead of being split into one entry per line, and Error Reporting picks it up from the message.
 *
 * <p>Enabled on Cloud Run with
 * {@code LOGGING_STRUCTURED_FORMAT_CONSOLE=com.example.durakgame.logging.CloudLoggingJsonFormatter};
 * local runs keep the human-readable console format.
 */
public class CloudLoggingJsonFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private final JsonWriter<ILoggingEvent> writer = JsonWriter.<ILoggingEvent>of(members -> {
        members.add("severity", event -> severity(event.getLevel()));
        members.add("message", CloudLoggingJsonFormatter::message);
        members.add("time", event -> event.getInstant().toString());
        members.add("logger", ILoggingEvent::getLoggerName);
        members.add("thread", ILoggingEvent::getThreadName);
    }).withNewLineAtEnd();

    @Override
    public String format(ILoggingEvent event) {
        return writer.writeToString(event);
    }

    static String severity(Level level) {
        if (level == null) {
            return "DEFAULT";
        }
        return switch (level.toInt()) {
            case Level.ERROR_INT -> "ERROR";
            case Level.WARN_INT -> "WARNING";
            case Level.INFO_INT -> "INFO";
            default -> "DEBUG";
        };
    }

    private static String message(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        IThrowableProxy throwable = event.getThrowableProxy();
        return throwable == null ? message : message + "\n" + ThrowableProxyUtil.asString(throwable);
    }
}
