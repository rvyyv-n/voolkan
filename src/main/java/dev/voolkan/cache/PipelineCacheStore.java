/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.cache;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * On-disk storage for the Vulkan pipeline cache blob. Pure Java (no Vulkan calls) so it can be checked
 * without a device.
 *
 * <p>File layout (little endian): 8-byte magic, u32 payload length, u32 CRC-32 of the payload, payload.
 * The payload is the blob returned by {@code vkGetPipelineCacheData}. The wrapper catches truncated and
 * corrupted files; the Vulkan header inside the payload (spec 10.6: header size, header version = 1,
 * vendor ID, device ID, pipeline cache UUID) is compared with the current device so a cache written by
 * another GPU or driver build is never handed to the driver.
 */
public final class PipelineCacheStore {
    private static final byte[] MAGIC = {'V', 'O', 'O', 'L', 'P', 'C', '0', '1'};
    private static final int WRAPPER_SIZE = MAGIC.length + 4 + 4;
    private static final int UUID_SIZE = 16;
    private static final int VK_HEADER_SIZE = 16 + UUID_SIZE;
    private static final int MAX_PAYLOAD = 256 * 1024 * 1024;

    private PipelineCacheStore() {
    }

    /** Identity of the device a cache must match. */
    public record DeviceKey(int vendorId, int deviceId, byte[] cacheUuid) {
    }

    /** Wraps a pipeline cache blob for writing. */
    public static byte[] encode(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);

        ByteBuffer out = ByteBuffer.allocate(WRAPPER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(MAGIC);
        out.putInt(payload.length);
        out.putInt((int) crc.getValue());
        out.put(payload);
        return out.array();
    }

    /**
     * Unwraps and validates a file read from disk.
     *
     * @return the pipeline cache blob, or {@code null} (with the reason passed to {@code reject}) if the
     * file is truncated, corrupt, or was written for a different device or driver.
     */
    public static byte[] decode(byte[] file, DeviceKey device, Consumer<String> reject) {
        if (file.length < WRAPPER_SIZE) {
            reject.accept("file too short (" + file.length + " bytes)");
            return null;
        }

        ByteBuffer in = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            reject.accept("bad magic");
            return null;
        }

        int length = in.getInt();
        int expectedCrc = in.getInt();
        if (length < VK_HEADER_SIZE || length > MAX_PAYLOAD || length != file.length - WRAPPER_SIZE) {
            reject.accept("bad payload length " + length + " (file has " + (file.length - WRAPPER_SIZE) + ")");
            return null;
        }

        byte[] payload = new byte[length];
        in.get(payload);

        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != expectedCrc) {
            reject.accept("checksum mismatch");
            return null;
        }

        ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = header.getInt();
        int headerVersion = header.getInt();
        int vendorId = header.getInt();
        int deviceId = header.getInt();
        byte[] uuid = new byte[UUID_SIZE];
        header.get(uuid);

        if (headerSize < VK_HEADER_SIZE || headerSize > length) {
            reject.accept("bad Vulkan header size " + headerSize);
        } else if (headerVersion != 1) {
            reject.accept("unknown Vulkan header version " + headerVersion);
        } else if (vendorId != device.vendorId() || deviceId != device.deviceId()) {
            reject.accept("written for another GPU (vendor 0x" + Integer.toHexString(vendorId)
                          + ", device 0x" + Integer.toHexString(deviceId) + ")");
        } else if (!Arrays.equals(uuid, device.cacheUuid())) {
            reject.accept("written by another driver build (cache UUID differs)");
        } else {
            return payload;
        }

        return null;
    }

    /** Reads and validates the cache file. Never throws: any problem means "start with an empty cache". */
    public static byte[] load(Path path, DeviceKey device, Consumer<String> reject) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }

            long size = Files.size(path);
            if (size > MAX_PAYLOAD + WRAPPER_SIZE) {
                reject.accept("file too large (" + size + " bytes)");
                return null;
            }

            return decode(Files.readAllBytes(path), device, reject);
        } catch (IOException | RuntimeException e) {
            reject.accept("read failed: " + e);
            return null;
        }
    }

    /** Writes the cache through a temporary file so a crash mid-write cannot leave a half-written cache. */
    public static boolean save(Path path, byte[] payload, Consumer<String> error) {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.write(tmp, encode(payload));
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
