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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * On-disk ledger of observed Vulkan graphics pipeline states, used during world-load warm-up to
 * pre-build {@code VkPipeline} handles instead of compiling them lazily mid-frame.
 *
 * <p>Stored at {@code <gameDir>/voolkan/pipeline_states.bin}.
 *
 * <p>File layout (little endian):
 * <ul>
 *   <li>8-byte magic {@code VOOLPS01}</li>
 *   <li>u32 format version ({@link #FORMAT_VERSION})</li>
 *   <li>u32 payload length in bytes</li>
 *   <li>u32 CRC-32 of payload</li>
 *   <li>payload: u32 entry count, followed by {@code count} entries, each:
 *       u16 UTF-8 byte length of {@code pipelineName}, UTF-8 bytes, and 8 x i32
 *       ({@code assemblyRasterState, blendState, depthState, logicOp, colorMask,
 *       colorFormat, depthFormat, dependencyKind})</li>
 * </ul>
 *
 * <p>All I/O and validation errors are absorbed; this class never throws into rendering.
 */
public final class PipelineStateStore {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 4096;

    private static final byte[] MAGIC = {'V', 'O', 'O', 'L', 'P', 'S', '0', '1'};
    private static final int HEADER_SIZE = MAGIC.length + 4 + 4 + 4;
    private static final int MAX_NAME_BYTES = 512;
    private static final int FIXED_ENTRY_BYTES = 2 + (8 * 4);
    private static final int MAX_PAYLOAD = 4 + MAX_ENTRIES * (FIXED_ENTRY_BYTES + MAX_NAME_BYTES);

    private static final Set<StateRecord> OBSERVED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);

    private static volatile Path cachedPath;

    private PipelineStateStore() {
    }

    /** Persisted identity of a graphics pipeline state observed at runtime. */
    public record StateRecord(
            String pipelineName,
            int assemblyRasterState,
            int blendState,
            int depthState,
            int logicOp,
            int colorMask,
            int colorFormat,
            int depthFormat,
            int dependencyKind) {
    }

    public static Path getStorePath() {
        Path path = cachedPath;
        if (path == null) {
            path = FabricLoader.getInstance().getGameDir().resolve("voolkan").resolve("pipeline_states.bin");
            cachedPath = path;
        }
        return path;
    }

    /** Records a newly compiled pipeline state so it can be persisted on shutdown. */
    public static void record(String pipelineName,
                              int assemblyRasterState,
                              int blendState,
                              int depthState,
                              int logicOp,
                              int colorMask,
                              int colorFormat,
                              int depthFormat,
                              int dependencyKind) {
        if (pipelineName == null || pipelineName.isEmpty()) {
            return;
        }
        if (colorFormat == 0 && depthFormat == 0) {
            return;
        }
        if (OBSERVED.size() >= MAX_ENTRIES) {
            return;
        }

        byte[] nameBytes = pipelineName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length == 0 || nameBytes.length > MAX_NAME_BYTES) {
            return;
        }

        StateRecord rec = new StateRecord(
                pipelineName,
                assemblyRasterState,
                blendState,
                depthState,
                logicOp,
                colorMask,
                colorFormat,
                depthFormat,
                dependencyKind);

        if (OBSERVED.add(rec)) {
            DIRTY.set(true);
        }
    }

    /** Loads the on-disk state ledger at most once per session and merges entries into memory. */
    public static void loadOnce() {
        if (!LOADED.compareAndSet(false, true)) {
            return;
        }

        Path path = getStorePath();
        List<StateRecord> loaded = load(path,
                reason -> Initializer.LOGGER.warn("Ignoring pipeline state ledger {}: {}", path, reason));
        for (StateRecord rec : loaded) {
            if (OBSERVED.size() >= MAX_ENTRIES) {
                break;
            }
            OBSERVED.add(rec);
        }
    }

    /** Returns a point-in-time snapshot of all known states (loaded plus newly observed). */
    public static List<StateRecord> getObservedSnapshot() {
        return List.copyOf(OBSERVED);
    }

    /** Saves the ledger if any new states were observed since startup. Never throws. */
    public static void saveIfDirty() {
        if (!DIRTY.get() || OBSERVED.isEmpty()) {
            return;
        }

        // Ensure previously saved states are merged before writing so a short session doesn't drop them
        loadOnce();

        List<StateRecord> snapshot = new ArrayList<>(Math.min(OBSERVED.size(), MAX_ENTRIES));
        for (StateRecord rec : OBSERVED) {
            if (snapshot.size() >= MAX_ENTRIES) {
                break;
            }
            snapshot.add(rec);
        }

        Path path = getStorePath();
        if (save(path, snapshot, error -> Initializer.LOGGER.warn("Pipeline state ledger {}: {}", path, error))) {
            DIRTY.set(false);
            Initializer.LOGGER.info("Saved pipeline state ledger: {} entries", snapshot.size());
        }
    }

    /** Encodes a list of state records into the versioned, checksummed binary format. */
    public static byte[] encode(List<StateRecord> records) {
        List<byte[]> encodedNames = new ArrayList<>(Math.min(records.size(), MAX_ENTRIES));
        List<StateRecord> validRecords = new ArrayList<>(Math.min(records.size(), MAX_ENTRIES));
        int payloadSize = 4;

        for (StateRecord rec : records) {
            if (validRecords.size() >= MAX_ENTRIES) {
                break;
            }
            if (rec == null || rec.pipelineName() == null || rec.pipelineName().isEmpty()) {
                continue;
            }
            byte[] nameBytes = rec.pipelineName().getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length == 0 || nameBytes.length > MAX_NAME_BYTES) {
                continue;
            }
            encodedNames.add(nameBytes);
            validRecords.add(rec);
            payloadSize += FIXED_ENTRY_BYTES + nameBytes.length;
        }

        ByteBuffer payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(validRecords.size());
        for (int i = 0; i < validRecords.size(); i++) {
            StateRecord rec = validRecords.get(i);
            byte[] nameBytes = encodedNames.get(i);
            payload.putShort((short) nameBytes.length);
            payload.put(nameBytes);
            payload.putInt(rec.assemblyRasterState());
            payload.putInt(rec.blendState());
            payload.putInt(rec.depthState());
            payload.putInt(rec.logicOp());
            payload.putInt(rec.colorMask());
            payload.putInt(rec.colorFormat());
            payload.putInt(rec.depthFormat());
            payload.putInt(rec.dependencyKind());
        }

        byte[] payloadBytes = payload.array();
        CRC32 crc = new CRC32();
        crc.update(payloadBytes);

        ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE + payloadBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.putInt(FORMAT_VERSION);
        out.putInt(payloadBytes.length);
        out.putInt((int) crc.getValue());
        out.put(payloadBytes);
        return out.array();
    }

    /**
     * Decodes and validates a state ledger byte array. Returns an empty list (invoking {@code reject})
     * on any corruption, truncation, or version mismatch.
     */
    public static List<StateRecord> decode(byte[] fileBytes, Consumer<String> reject) {
        if (fileBytes.length < HEADER_SIZE) {
            reject.accept("file too short (" + fileBytes.length + " bytes)");
            return List.of();
        }

        ByteBuffer in = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            reject.accept("bad magic");
            return List.of();
        }

        int version = in.getInt();
        if (version != FORMAT_VERSION) {
            reject.accept("format version mismatch (" + version + ")");
            return List.of();
        }

        int payloadLength = in.getInt();
        int expectedCrc = in.getInt();
        if (payloadLength < 4 || payloadLength > MAX_PAYLOAD || payloadLength != fileBytes.length - HEADER_SIZE) {
            reject.accept("bad payload length " + payloadLength);
            return List.of();
        }

        byte[] payloadBytes = new byte[payloadLength];
        in.get(payloadBytes);

        CRC32 crc = new CRC32();
        crc.update(payloadBytes);
        if ((int) crc.getValue() != expectedCrc) {
            reject.accept("CRC-32 mismatch");
            return List.of();
        }

        ByteBuffer payload = ByteBuffer.wrap(payloadBytes).order(ByteOrder.LITTLE_ENDIAN);
        int count = payload.getInt();
        if (count < 0 || count > MAX_ENTRIES) {
            reject.accept("bad entry count " + count);
            return List.of();
        }

        List<StateRecord> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (payload.remaining() < 2) {
                reject.accept("truncated entry " + i);
                return List.of();
            }
            int nameLen = payload.getShort() & 0xFFFF;
            if (nameLen == 0 || nameLen > MAX_NAME_BYTES || payload.remaining() < nameLen + (8 * 4)) {
                reject.accept("invalid entry " + i + " (nameLen=" + nameLen + ")");
                return List.of();
            }

            byte[] nameBytes = new byte[nameLen];
            payload.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            int assemblyRasterState = payload.getInt();
            int blendState = payload.getInt();
            int depthState = payload.getInt();
            int logicOp = payload.getInt();
            int colorMask = payload.getInt();
            int colorFormat = payload.getInt();
            int depthFormat = payload.getInt();
            int dependencyKind = payload.getInt();

            result.add(new StateRecord(
                    name,
                    assemblyRasterState,
                    blendState,
                    depthState,
                    logicOp,
                    colorMask,
                    colorFormat,
                    depthFormat,
                    dependencyKind));
        }

        if (payload.hasRemaining()) {
            reject.accept("trailing bytes (" + payload.remaining() + ")");
            return List.of();
        }

        return result;
    }

    /** Reads and validates the state ledger file. Never throws. */
    public static List<StateRecord> load(Path path, Consumer<String> reject) {
        try {
            if (!Files.isRegularFile(path)) {
                return List.of();
            }

            long size = Files.size(path);
            if (size < HEADER_SIZE || size > (long) MAX_PAYLOAD + HEADER_SIZE) {
                reject.accept("unexpected size " + size);
                return List.of();
            }

            return decode(Files.readAllBytes(path), reject);
        } catch (IOException | RuntimeException e) {
            reject.accept("read failed: " + e);
            return List.of();
        }
    }

    /** Atomically writes the state ledger through a temporary file. Never throws. */
    public static boolean save(Path path, List<StateRecord> records, Consumer<String> error) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.write(tmp, encode(records));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            error.accept("write failed: " + e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return false;
        }
    }
}
