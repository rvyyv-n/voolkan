/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.cache;

import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.Initializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * On-disk cache for compiled SPIR-V shader binaries.
 *
 * <p>Stored under {@code <gameDir>/voolkan/shader_cache/<sha256>.vspv}.
 *
 * <p>File layout (little endian):
 * <ul>
 *   <li>8-byte magic {@code VOOLSPV1}</li>
 *   <li>u32 format version ({@link #FORMAT_VERSION})</li>
 *   <li>u32 payload length in bytes (must be a positive multiple of 4)</li>
 *   <li>u32 CRC-32 of the SPIR-V payload</li>
 *   <li>SPIR-V payload (validated for SPIR-V magic {@code 0x07230203})</li>
 * </ul>
 *
 * <p>All I/O errors are absorbed and fall back to normal compilation; this class never throws into rendering.
 */
public final class ShaderBinaryCache {
    public static final int FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'V', 'O', 'O', 'L', 'S', 'P', 'V', '1'};
    private static final int HEADER_SIZE = MAGIC.length + 4 + 4 + 4;
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;
    private static final String FILE_SUFFIX = ".vspv";

    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger MISSES = new AtomicInteger();
    private static final AtomicBoolean PRUNED_ON_START = new AtomicBoolean();
    private static final AtomicBoolean WARNED_CORRUPT = new AtomicBoolean();
    private static final AtomicBoolean WARNED_IO = new AtomicBoolean();
    private static final AtomicBoolean SUMMARY_LOGGED = new AtomicBoolean();

    private static volatile String cachedModVersion;
    private static volatile Path cachedDir;

    private ShaderBinaryCache() {
    }

    public static boolean isEnabled() {
        String property = System.getProperty("voolkan.shaderCache");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }
        return Initializer.CONFIG != null && Initializer.CONFIG.pipelineCache;
    }

    public static String getModVersion() {
        String version = cachedModVersion;
        if (version == null) {
            try {
                version = FabricLoader.getInstance()
                        .getModContainer("voolkan")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown");
            } catch (RuntimeException e) {
                version = "unknown";
            }
            cachedModVersion = version;
        }
        return version;
    }

    public static Path getCacheDir() {
        Path dir = cachedDir;
        if (dir == null) {
            dir = FabricLoader.getInstance().getGameDir().resolve("voolkan").resolve("shader_cache");
            cachedDir = dir;
        }
        return dir;
    }

    /**
     * Computes the SHA-256 hex key for a shader compile.
     *
     * <p>Exposed so a pipeline warm-up pass can look up a cached binary by key without invoking shaderc.
     */
    public static String computeKey(String modVersion,
                                    int targetEnvVersion,
                                    boolean debug,
                                    boolean optimizations,
                                    int shaderKind,
                                    String filename,
                                    String source,
                                    byte[] includeDigest) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            updateInt(sha, FORMAT_VERSION);
            updateString(sha, modVersion != null ? modVersion : "");
            updateInt(sha, targetEnvVersion);
            sha.update((byte) (debug ? 1 : 0));
            sha.update((byte) (optimizations ? 1 : 0));
            updateInt(sha, shaderKind);
            updateString(sha, filename != null ? filename : "");
            updateString(sha, source != null ? source : "");
            if (includeDigest != null) {
                updateInt(sha, includeDigest.length);
                sha.update(includeDigest);
            } else {
                updateInt(sha, 0);
            }
            return toHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Looks up a validated SPIR-V binary by key. Returns {@code null} on miss, corruption, or I/O error. */
    public static byte[] lookup(String key) {
        if (!isEnabled() || key == null || key.isEmpty()) {
            return null;
        }

        Path dir = getCacheDir();
        ensurePrunedOnce(dir);
        Path path = dir.resolve(key + FILE_SUFFIX);

        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            long size = Files.size(path);
            if (size < HEADER_SIZE || size > (long) MAX_PAYLOAD + HEADER_SIZE) {
                warnCorruptOnce(path, "unexpected size " + size);
                return null;
            }
            byte[] raw = Files.readAllBytes(path);
            return decode(raw, reason -> warnCorruptOnce(path, reason));
        } catch (IOException | RuntimeException e) {
            warnIoOnce("read failed for " + path + ": " + e);
            return null;
        }
    }

    /** Atomically stores a SPIR-V binary under the given key. Never throws. */
    public static boolean store(String key, byte[] spirvBytes) {
        if (!isEnabled() || key == null || key.isEmpty() || !isValidSpirv(spirvBytes)) {
            return false;
        }

        Path dir = getCacheDir();
        ensurePrunedOnce(dir);
        Path target = dir.resolve(key + FILE_SUFFIX);
        Path tmp = dir.resolve(key + "." + Thread.currentThread().threadId() + ".tmp");

        try {
            Files.createDirectories(dir);
            Files.write(tmp, encode(spirvBytes));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            warnIoOnce("write failed for " + target + ": " + e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    public static byte[] encode(byte[] spirvBytes) {
        CRC32 crc = new CRC32();
        crc.update(spirvBytes);

        ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE + spirvBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.putInt(FORMAT_VERSION);
        out.putInt(spirvBytes.length);
        out.putInt((int) crc.getValue());
        out.put(spirvBytes);
        return out.array();
    }

    public static byte[] decode(byte[] fileBytes, Consumer<String> reject) {
        if (fileBytes.length < HEADER_SIZE) {
            reject.accept("file too short (" + fileBytes.length + " bytes)");
            return null;
        }

        ByteBuffer in = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            reject.accept("bad magic");
            return null;
        }

        int version = in.getInt();
        if (version != FORMAT_VERSION) {
            reject.accept("format version mismatch (" + version + ")");
            return null;
        }

        int length = in.getInt();
        int expectedCrc = in.getInt();
        if (length < 4 || length > MAX_PAYLOAD || (length & 3) != 0 || length != fileBytes.length - HEADER_SIZE) {
            reject.accept("bad payload length " + length);
            return null;
        }

        byte[] payload = new byte[length];
        in.get(payload);

        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != expectedCrc) {
            reject.accept("CRC-32 mismatch");
            return null;
        }

        if (!isValidSpirv(payload)) {
            reject.accept("invalid SPIR-V magic");
            return null;
        }

        return payload;
    }

    public static boolean isValidSpirv(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || (bytes.length & 3) != 0) {
            return false;
        }
        int magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return magic == SPIRV_MAGIC;
    }

    public static void recordHit() {
        HITS.incrementAndGet();
    }

    public static void recordMiss() {
        MISSES.incrementAndGet();
    }

    public static int getHits() {
        return HITS.get();
    }

    public static int getMisses() {
        return MISSES.get();
    }

    public static void logSummary() {
        int hits = HITS.get();
        int misses = MISSES.get();
        if (hits + misses > 0 && SUMMARY_LOGGED.compareAndSet(false, true)) {
            Initializer.LOGGER.info("Shader binary cache: {} hits, {} misses", hits, misses);
        }
    }

    private static void ensurePrunedOnce(Path dir) {
        if (!PRUNED_ON_START.compareAndSet(false, true)) {
            return;
        }
        pruneToLimit(dir, MAX_CACHE_BYTES);
    }

    private static void pruneToLimit(Path dir, long maxBytes) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            record Entry(Path path, long size, FileTime mtime) {
            }

            List<Entry> entries = new ArrayList<>();
            long total = 0;
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    long size = Files.size(p);
                    FileTime mtime = Files.getLastModifiedTime(p);
                    entries.add(new Entry(p, size, mtime));
                    total += size;
                } catch (IOException ignored) {
                }
            }

            if (total <= maxBytes) {
                return;
            }

            entries.sort(Comparator.comparing(Entry::mtime));
            int deleted = 0;
            for (Entry entry : entries) {
                if (total <= maxBytes) {
                    break;
                }
                try {
                    if (Files.deleteIfExists(entry.path())) {
                        total -= entry.size();
                        deleted++;
                    }
                } catch (IOException ignored) {
                }
            }

            if (deleted > 0) {
                Initializer.LOGGER.info("Pruned {} old entries from shader binary cache", deleted);
            }
        } catch (IOException | RuntimeException e) {
            warnIoOnce("cache prune failed: " + e);
        }
    }

    private static void warnCorruptOnce(Path path, String reason) {
        if (WARNED_CORRUPT.compareAndSet(false, true)) {
            Initializer.LOGGER.warn("Ignoring corrupt shader cache entry {}: {} (further mismatches will be ignored silently)", path, reason);
        } else {
            Initializer.LOGGER.debug("Ignoring corrupt shader cache entry {}: {}", path, reason);
        }
    }

    private static void warnIoOnce(String message) {
        if (WARNED_IO.compareAndSet(false, true)) {
            Initializer.LOGGER.warn("Shader binary cache: {}", message);
        } else {
            Initializer.LOGGER.debug("Shader binary cache: {}", message);
        }
    }

    private static void updateInt(MessageDigest md, int value) {
        md.update((byte) (value >>> 24));
        md.update((byte) (value >>> 16));
        md.update((byte) (value >>> 8));
        md.update((byte) value);
    }

    private static void updateString(MessageDigest md, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        updateInt(md, bytes.length);
        md.update(bytes);
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(hex);
    }
}
