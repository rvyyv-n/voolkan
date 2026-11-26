package net.vulkanmod.vulkan.shader;

/*
 * This file is part of VulkanMod (https://github.com/xCollateral/VulkanMod),
 * licensed under the GNU LGPL-3.0. Modified by the voolkan project, 2026:
 * cache compiled SPIR-V binaries on disk, support owned ByteBuffer SPIRV instances, release shaderc results on free, and fix shaderc Vulkan 1.2 target environment on macOS.
 */

import dev.voolkan.cache.ShaderBinaryCache;
import dev.voolkan.compat.MacPerfOpts;
import net.vulkanmod.Initializer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SPIRVUtils {
    private static final boolean DEBUG = Boolean.getBoolean("voolkan.shaderDebug");
    private static final boolean OPTIMIZATIONS = !Boolean.getBoolean("voolkan.disableShaderOptimizations");
    /** Distinguishes macOS Vulkan 1.2 SPIR-V cache entries from pre-fix binaries without affecting Windows keys. */
    private static final int MAC_SHADER_ENV_CACHE_TAG = 0x40000000;

    private static long compiler;
    private static long options;

    //The dedicated Includer and Releaser Inner Classes used to Initialise #include Support for ShaderC
    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static final List<String> includePaths = new CopyOnWriteArrayList<>();
    private static final AtomicInteger includePathsVersion = new AtomicInteger();
    private static volatile int hashedIncludeVersion = -1;
    private static volatile byte[] cachedIncludeDigest;

    static {
        initCompiler();
    }

    private static void initCompiler() {
        compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if (options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if (OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if (DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        if (MacPerfOpts.shaderOptsMac()) {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        } else {
            shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, VK12.VK_API_VERSION_1_2);
        }
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    public static void addIncludePath(String path) {
        URL url = SPIRVUtils.class.getResource(path);

        if (url != null) {
            includePaths.add(url.toExternalForm());
            includePathsVersion.incrementAndGet();
        }
    }

    /**
     * Hashes every file under all registered include directories once per include-path set.
     *
     * <p>The include directory holds only a handful of small GLSL headers, so hashing all of them up front
     * is negligible, guarantees that any header edit invalidates affected entries, and lets callers compute
     * the exact cache key before invoking shaderc (needed for future pipeline warm-up).
     *
     * @return SHA-256 digest of all include files, or {@code null} if an include directory could not be walked
     * (in which case disk caching is skipped so a stale binary can never be returned).
     */
    private static byte[] getIncludeDigest() {
        int version = includePathsVersion.get();
        if (hashedIncludeVersion == version) {
            return cachedIncludeDigest;
        }

        synchronized (includePaths) {
            version = includePathsVersion.get();
            if (hashedIncludeVersion == version) {
                return cachedIncludeDigest;
            }

            byte[] digest = computeIncludeDigest();
            cachedIncludeDigest = digest;
            hashedIncludeVersion = version;
            return digest;
        }
    }

    private static byte[] computeIncludeDigest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String includePath : includePaths) {
                Path dir = Paths.get(new URI(includePath));
                if (!Files.isDirectory(dir)) {
                    return null;
                }
                List<Path> files = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
                files.sort(Comparator.comparing(p -> dir.relativize(p).toString().replace('\\', '/')));
                for (Path file : files) {
                    String rel = dir.relativize(file).toString().replace('\\', '/');
                    byte[] relBytes = rel.getBytes(StandardCharsets.UTF_8);
                    sha.update((byte) (relBytes.length >>> 24));
                    sha.update((byte) (relBytes.length >>> 16));
                    sha.update((byte) (relBytes.length >>> 8));
                    sha.update((byte) relBytes.length);
                    sha.update(relBytes);

                    byte[] content = Files.readAllBytes(file);
                    sha.update((byte) (content.length >>> 24));
                    sha.update((byte) (content.length >>> 16));
                    sha.update((byte) (content.length >>> 8));
                    sha.update((byte) content.length);
                    sha.update(content);
                }
            }
            return sha.digest();
        } catch (IOException | URISyntaxException | RuntimeException | NoSuchAlgorithmException e) {
            Initializer.LOGGER.warn("Could not hash shader include directory; disabling SPIR-V disk cache for this run: {}", e.toString());
            return null;
        }
    }

    /** Computes the disk cache key for the given shader, or {@code null} if include hashing failed. */
    public static String computeCacheKey(String filename, String source, ShaderKind shaderKind) {
        byte[] includeDigest = getIncludeDigest();
        if (includeDigest == null) {
            return null;
        }
        int targetEnvVersion = MacPerfOpts.shaderOptsMac()
                ? (VK12.VK_API_VERSION_1_2 | MAC_SHADER_ENV_CACHE_TAG)
                : VK12.VK_API_VERSION_1_2;
        return ShaderBinaryCache.computeKey(
                ShaderBinaryCache.getModVersion(),
                targetEnvVersion,
                DEBUG,
                OPTIMIZATIONS,
                shaderKind.kind,
                filename,
                source,
                includeDigest
        );
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        if (source == null) {
            throw new NullPointerException("Source for %s.%s is null".formatted(filename, shaderKind));
        }

        String cacheKey = null;
        if (ShaderBinaryCache.isEnabled()) {
            cacheKey = computeCacheKey(filename, source, shaderKind);
            if (cacheKey != null) {
                byte[] cached = ShaderBinaryCache.lookup(cacheKey);
                if (cached != null) {
                    ShaderBinaryCache.recordHit();
                    return SPIRV.fromBytes(cached);
                }
            }
        }

        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if (result == NULL) {
            throw new RuntimeException("Failed to compile shader %s into SPRI-V".formatted(filename));
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String errorMessage = shaderc_result_get_error_message(result);
            shaderc_result_release(result);
            throw new RuntimeException("Failed to compile shader %s into SPIR-V:\n\t%s".formatted(filename, errorMessage));
        }

        ByteBuffer bytecode = shaderc_result_get_bytes(result);
        if (cacheKey != null && bytecode != null) {
            ShaderBinaryCache.recordMiss();
            byte[] copy = new byte[bytecode.remaining()];
            bytecode.slice().get(copy);
            ShaderBinaryCache.store(cacheKey, copy);
        }

        return new SPIRV(result, bytecode);
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {

        private static final int MAX_PATH_LENGTH = 4096; //Maximum Linux/Unix Path Length

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            var requesting = memASCII(requesting_source);
            var requested = memASCII(requested_source);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                Path path;

                for (String includePath : includePaths) {
                    path = Paths.get(new URI(String.format("%s%s", includePath, requested)));

                    if (Files.exists(path)) {
                        byte[] bytes = Files.readAllBytes(path);

                        return ShadercIncludeResult.malloc(stack)
                                                   .source_name(stack.ASCII(requested))
                                                   .content(stack.bytes(bytes))
                                                   .user_data(user_data).address();
                    }
                }
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException(String.format("%s: Unable to find %s in include paths", requesting, requested));
        }
    }

    //TODO: Don't actually need the Releaser at all, (MemoryStack frees this for us)
    //But ShaderC won't let us create the Includer without a corresponding Releaser, (so we need it anyway)
    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {

        @Override
        public void invoke(long user_data, long include_result) {
            //TODO:Maybe dump Shader Compiled Binaries here to a .Misc Diretcory to allow easy caching.recompilation...
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private final boolean ownedBuffer;
        private ByteBuffer bytecode;
        private boolean freed;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this(handle, bytecode, false);
        }

        private SPIRV(long handle, ByteBuffer bytecode, boolean ownedBuffer) {
            this.handle = handle;
            this.bytecode = bytecode;
            this.ownedBuffer = ownedBuffer;
        }

        public static SPIRV fromBytes(byte[] bytes) {
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes).flip();
            return new SPIRV(NULL, buffer, true);
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
            if (freed) {
                return;
            }
            freed = true;
            if (ownedBuffer) {
                if (bytecode != null) {
                    MemoryUtil.memFree(bytecode);
                }
            } else if (handle != NULL) {
                shaderc_result_release(handle);
            }
            bytecode = null; // Help the GC
        }
    }

}