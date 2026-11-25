/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.power;

import net.vulkanmod.Initializer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * macOS {@link PowerSource} backed by asynchronous {@code /usr/bin/pmset -g batt} polling on a
 * single daemon worker thread.
 * <p>
 * {@link #isOnBattery()} never performs subprocess I/O on the render thread: it returns the latest
 * cached state immediately and triggers a background refresh when stale. Desktop Macs without a
 * battery or UPS mark themselves unsupported once the initial startup probe completes.
 */
public final class MacosPowerSource implements PowerSource {

    public enum PowerState {
        AC,
        BATTERY,
        UNKNOWN
    }

    private static final String PMSET_PATH = "/usr/bin/pmset";
    private static final long POLL_INTERVAL_NS = 5_000_000_000L; // 5 s
    private static final long PROCESS_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(1L);
    private static final long POLL_SLICE_NS = TimeUnit.MILLISECONDS.toNanos(25L);
    private static final int MAX_OUTPUT_BYTES = 4096;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ExecutorService executor;
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile boolean supported = true;
    private volatile boolean onBattery = false;
    private volatile long lastPollNs = 0L;

    // Only accessed on the single worker thread
    private int consecutiveFailures = 0;

    private MacosPowerSource() {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voolkan-macos-power");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Creates the macOS power-source probe and starts its initial asynchronous check. */
    public static MacosPowerSource create() {
        MacosPowerSource source = new MacosPowerSource();
        source.triggerRefresh();
        return source;
    }

    @Override
    public boolean isSupported() {
        return this.supported;
    }

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public boolean isOnBattery() {
        boolean current = this.onBattery;
        if (this.supported && !this.closed.get()) {
            long prev = this.lastPollNs;
            if (prev == 0L || System.nanoTime() - prev >= POLL_INTERVAL_NS) {
                triggerRefresh();
            }
        }
        return current;
    }

    @Override
    public void close() {
        shutdown();
    }

    /** Stops background polling and shuts down the daemon worker thread. */
    public void shutdown() {
        if (this.closed.compareAndSet(false, true)) {
            this.executor.shutdownNow();
        }
    }

    private void triggerRefresh() {
        if (this.closed.get() || !this.supported) {
            return;
        }
        if (!this.pollInFlight.compareAndSet(false, true)) {
            return;
        }
        long previousPollNs = this.lastPollNs;
        this.lastPollNs = System.nanoTime();
        try {
            this.executor.execute(this::runPoll);
        } catch (RejectedExecutionException ignored) {
            this.lastPollNs = previousPollNs;
            this.pollInFlight.set(false);
        }
    }

    private void runPoll() {
        try {
            if (this.closed.get() || !this.supported) {
                return;
            }

            Process process = new ProcessBuilder(PMSET_PATH, "-g", "batt")
                    .redirectErrorStream(true)
                    .start();

            String output = readBoundedOutput(process);
            if (output == null || process.exitValue() != 0) {
                recordFailure(null);
                return;
            }

            this.consecutiveFailures = 0;
            this.lastPollNs = System.nanoTime();

            if (!hasBattery(output)) {
                this.onBattery = false;
                this.supported = false;
                shutdown();
                return;
            }

            PowerState state = parsePowerState(output);
            this.onBattery = (state == PowerState.BATTERY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            recordFailure(e);
        } finally {
            this.pollInFlight.set(false);
        }
    }

    private static String readBoundedOutput(Process process) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        byte[] chunk = new byte[512];
        long deadlineNs = System.nanoTime() + PROCESS_TIMEOUT_NS;

        try (InputStream in = process.getInputStream()) {
            boolean exited = false;
            while (true) {
                while (in.available() > 0 && System.nanoTime() < deadlineNs) {
                    int n = in.read(chunk);
                    if (n <= 0) {
                        break;
                    }
                    int remaining = MAX_OUTPUT_BYTES - buffer.size();
                    if (remaining > 0) {
                        buffer.write(chunk, 0, Math.min(n, remaining));
                    }
                }

                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) {
                    exited = process.waitFor(0L, TimeUnit.MILLISECONDS);
                    break;
                }

                if (process.waitFor(Math.min(remainingNs, POLL_SLICE_NS), TimeUnit.NANOSECONDS)) {
                    exited = true;
                    break;
                }
            }

            if (!exited) {
                process.destroyForcibly();
                return null;
            }

            int n;
            while (buffer.size() < MAX_OUTPUT_BYTES && (n = in.read(chunk)) > 0) {
                int remaining = MAX_OUTPUT_BYTES - buffer.size();
                buffer.write(chunk, 0, Math.min(n, remaining));
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void recordFailure(Exception e) {
        this.onBattery = false;
        this.lastPollNs = System.nanoTime();
        this.consecutiveFailures++;
        if (this.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            if (e != null) {
                Initializer.LOGGER.warn("Disabling macOS battery probe after {} failures: {}",
                        this.consecutiveFailures, e.getMessage());
            } else {
                Initializer.LOGGER.warn("Disabling macOS battery probe after {} failed pmset polls",
                        this.consecutiveFailures);
            }
            this.supported = false;
            shutdown();
        }
    }

    /**
     * Parses the active power source from {@code pmset -g batt} output.
     */
    public static PowerState parsePowerState(String output) {
        if (output == null || output.isEmpty()) {
            return PowerState.UNKNOWN;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("'battery power'") || lower.contains("drawing from 'battery")) {
            return PowerState.BATTERY;
        }
        if (lower.contains("'ac power'") || lower.contains("drawing from 'ac")) {
            return PowerState.AC;
        }
        if (lower.contains("'ups power'")) {
            return PowerState.BATTERY;
        }
        if (lower.contains("discharging;")) {
            return PowerState.BATTERY;
        }
        if (lower.contains("charging;") || lower.contains("charged;") || lower.contains("ac attached")) {
            return PowerState.AC;
        }
        return PowerState.UNKNOWN;
    }

    /**
     * Returns whether {@code pmset -g batt} reports an internal battery or UPS.
     */
    public static boolean hasBattery(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("internalbattery")
                || lower.contains("battery power")
                || lower.contains("ups");
    }
}
