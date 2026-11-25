/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, thread-safe ring buffer of recent sanitized Vulkan / voolkan warnings and errors
 * included in one-click copied diagnostics.
 */
public final class DiagnosticLogBuffer {
    private static final int MAX_ENTRIES = 32;
    private static final int MAX_MESSAGE_CHARS = 360;
    private static final long START_NS = System.nanoTime();

    private static final Deque<String> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);
    private static final AtomicBoolean APPENDER_INSTALLED = new AtomicBoolean(false);

    private DiagnosticLogBuffer() {
    }

    /** Records a sanitized warning/error line in the ring buffer. */
    public static void record(String level, String source, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        String sanitized = DiagnosticSanitizer.sanitize(rawMessage)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (sanitized.isEmpty()) {
            return;
        }
        if (sanitized.length() > MAX_MESSAGE_CHARS) {
            sanitized = sanitized.substring(0, MAX_MESSAGE_CHARS - 3) + "...";
        }

        double elapsedSec = Math.max(0.0, (System.nanoTime() - START_NS) * 1.0e-9);
        String line = String.format(Locale.ROOT, "[+%6.1fs] [%s/%s] %s", elapsedSec, level, source, sanitized);

        synchronized (ENTRIES) {
            if (ENTRIES.size() >= MAX_ENTRIES) {
                ENTRIES.removeFirst();
            }
            ENTRIES.addLast(line);
        }
    }

    /** Returns an immutable snapshot of buffered entries in chronological order. */
    public static List<String> snapshot() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }

    /**
     * Attaches a lightweight Log4j2 appender to the {@code VulkanMod} logger to capture WARN/ERROR
     * events into the ring buffer. Absorbs all errors if a custom logging backend is active.
     */
    public static void installLogAppender() {
        if (!APPENDER_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            org.apache.logging.log4j.Logger rawLogger = LogManager.getLogger("VulkanMod");
            if (!(rawLogger instanceof Logger coreLogger)) {
                return;
            }
            AbstractAppender appender = new AbstractAppender(
                    "VoolkanDiagnosticRing",
                    null,
                    null,
                    true,
                    Property.EMPTY_ARRAY
            ) {
                @Override
                public void append(LogEvent event) {
                    if (event == null) {
                        return;
                    }
                    Level level = event.getLevel();
                    if (level == null || !level.isMoreSpecificThan(Level.WARN)) {
                        return;
                    }
                    String msg = event.getMessage() != null ? event.getMessage().getFormattedMessage() : "";
                    Throwable thrown = event.getThrown();
                    if (thrown != null && thrown.getMessage() != null && !msg.contains(thrown.getMessage())) {
                        msg = msg.isEmpty()
                                ? thrown.getClass().getSimpleName() + ": " + thrown.getMessage()
                                : msg + " (" + thrown.getClass().getSimpleName() + ": " + thrown.getMessage() + ")";
                    }
                    record(level.name(), "log", msg);
                }
            };
            appender.start();
            coreLogger.addAppender(appender);
        } catch (Throwable ignored) {
            // Custom launcher/logging bridge without log4j-core: Vulkan debugCallback & explicit hooks still work.
        }
    }
}
