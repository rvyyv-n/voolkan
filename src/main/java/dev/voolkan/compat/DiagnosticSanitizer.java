/*
 * voolkan: unofficial fork of VulkanMod (https://github.com/xCollateral/VulkanMod).
 * Licensed under the GNU LGPL-3.0.
 */
package dev.voolkan.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.config.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips ANSI escapes, local filesystem paths, file URIs, and OS usernames from diagnostic text
 * so copied reports and failure dialogs never leak personal information.
 */
public final class DiagnosticSanitizer {
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    // Directory segment allowing up to 3 spaces (e.g. "John Doe", "My Secret Drive", "Program Files (x86)")
    // only when terminated by a path separator so matches never swallow surrounding log prose.
    private static final String SEG = "[^\\s\\\\/\"'<>|\\]\\),;:]+(?:\\s+[^\\s\\\\/\"'<>|\\]\\),;:]+){0,3}";
    private static final String TAIL = "[^\\s\\\\/\"'<>|\\]\\),;]*";

    private static final Pattern FILE_URI = Pattern.compile(
            "(?i)file:/+(?:" + SEG + "[\\\\/])*" + TAIL);
    private static final Pattern WIN_USER_PATH = Pattern.compile(
            "(?i)[A-Z]:[\\\\/]+Users[\\\\/]+(?:" + SEG + "[\\\\/])*" + TAIL);
    private static final Pattern WIN_DRIVE_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])[A-Z]:[\\\\/](?:" + SEG + "[\\\\/])*" + TAIL);
    private static final Pattern WIN_UNC_PATH = Pattern.compile(
            "\\\\\\\\[A-Za-z0-9._-]+[\\\\/](?:" + SEG + "[\\\\/])*" + TAIL);
    private static final Pattern UNIX_USER_PATH = Pattern.compile(
            "(?i)(?<=^|[\\s(\"'=:,;\\[\\{])(?:/Users|/home|/root)(?:/(?:" + SEG + "/)*" + TAIL + ")?");
    private static final Pattern UNIX_SYS_PATH = Pattern.compile(
            "(?i)(?<=^|[\\s(\"'=:,;\\[\\{])/(?:Volumes|private/var|var/folders|var/tmp|tmp|opt|Applications|Library|System|mnt|media|run|etc|usr)/(?:" + SEG + "/)*" + TAIL);

    /** Common technical words that must not be redacted as bare tokens if they match a user's login name. */
    private static final Set<String> RESERVED_TECH_TOKENS = Set.of(
            "apple", "amd", "intel", "nvidia", "host", "gpu", "cpu", "auto", "none", "true", "false",
            "vulkan", "metal", "moltenvk", "linux", "windows", "macos", "user", "admin", "root",
            "default", "legacy", "dynamic", "memory", "device", "driver", "shader", "cache", "swapchain",
            "fifo", "mailbox", "immediate", "unified", "local", "native", "opengl", "wayland", "glfw", "java"
    );

    private record RootReplacement(Pattern pattern, String token, int length) {}

    private static volatile List<RootReplacement> cachedRoots;
    private static volatile Pattern cachedUsernamePattern;
    private static volatile boolean usernameChecked = false;

    private DiagnosticSanitizer() {
    }

    /**
     * Sanitizes untrusted/freeform text (logs, error messages, exceptions, env values):
     * strips ANSI codes, URIs, paths (including space-containing paths), and OS usernames.
     */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String text = sanitizePathsOnly(input);
        Pattern userPattern = getUsernamePattern();
        if (userPattern != null) {
            text = userPattern.matcher(text).replaceAll("<USER>");
        }
        return text;
    }

    /**
     * Sanitizes ANSI escapes, file URIs, known local root directories, and generic OS paths
     * without running bare-word username substitution across structured diagnostic keywords.
     */
    public static String sanitizePathsOnly(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String text = ANSI_ESCAPE.matcher(input).replaceAll("");

        // Scrub file:// URIs before literal root replacements so embedded roots don't fragment the URI.
        text = FILE_URI.matcher(text).replaceAll("<PATH>");

        for (RootReplacement root : getRootReplacements()) {
            text = root.pattern.matcher(text).replaceAll(Matcher.quoteReplacement(root.token));
        }

        // Replace literal OS username inside any remaining path segments before generic path regexes
        // so space-containing usernames without a trailing separator cannot leak trailing words.
        String rawUser = getRawUsername();
        if (rawUser != null && rawUser.length() >= 3) {
            text = scrubUsernameInPaths(text, rawUser);
        }

        text = WIN_USER_PATH.matcher(text).replaceAll("<USER_PATH>");
        text = WIN_UNC_PATH.matcher(text).replaceAll("<UNC_PATH>");
        text = WIN_DRIVE_PATH.matcher(text).replaceAll("<PATH>");
        text = UNIX_USER_PATH.matcher(text).replaceAll("<USER_PATH>");
        text = UNIX_SYS_PATH.matcher(text).replaceAll("<PATH>");

        return text;
    }

    /**
     * Sanitizes an environment variable value (e.g. {@code MVK_CONFIG_*}) so path-bearing values
     * are redacted completely while boolean/numeric settings remain readable.
     */
    public static String sanitizeEnvValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.startsWith("~")
                || (value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':')) {
            return "<REDACTED_PATH>";
        }
        return sanitize(value);
    }

    private static String scrubUsernameInPaths(String text, String rawUser) {
        Pattern p = Pattern.compile(
                "(?i)(?<=[\\\\/])" + Pattern.quote(rawUser) + "(?=[\\\\/\\s\"'<>|\\]\\),;:]|$)");
        return p.matcher(text).replaceAll("<USER>");
    }

    private static List<RootReplacement> getRootReplacements() {
        List<RootReplacement> roots = cachedRoots;
        if (roots != null) {
            return roots;
        }

        List<RootReplacement> built = new ArrayList<>();
        boolean ignoreCase = Platform.isWindows() || Platform.isMacOS();

        try {
            FabricLoader loader = FabricLoader.getInstance();
            if (loader != null) {
                addPathVariants(built, loader.getConfigDir(), "<CONFIG_DIR>", ignoreCase);
                addPathVariants(built, loader.getGameDir(), "<GAME_DIR>", ignoreCase);
            }
        } catch (Throwable ignored) {
        }

        addStringPathVariants(built, System.getProperty("user.home"), "<HOME>", ignoreCase);
        addStringPathVariants(built, System.getProperty("user.dir"), "<WORKDIR>", ignoreCase);
        addStringPathVariants(built, System.getProperty("java.home"), "<JAVA_HOME>", ignoreCase);
        addStringPathVariants(built, System.getProperty("java.io.tmpdir"), "<TMPDIR>", ignoreCase);
        addStringPathVariants(built, System.getenv("USERPROFILE"), "<HOME>", ignoreCase);
        addStringPathVariants(built, System.getenv("APPDATA"), "<APPDATA>", ignoreCase);
        addStringPathVariants(built, System.getenv("LOCALAPPDATA"), "<LOCALAPPDATA>", ignoreCase);
        addStringPathVariants(built, System.getenv("HOME"), "<HOME>", ignoreCase);

        built.sort(Comparator.comparingInt(RootReplacement::length).reversed());
        cachedRoots = built;
        return built;
    }

    private static void addPathVariants(List<RootReplacement> out, Path path, String token, boolean ignoreCase) {
        if (path == null) {
            return;
        }
        addStringPathVariants(out, path.toAbsolutePath().normalize().toString(), token, ignoreCase);
    }

    private static void addStringPathVariants(List<RootReplacement> out, String raw, String token, boolean ignoreCase) {
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        while (trimmed.length() > 3 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.length() < 4) {
            return;
        }
        int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        out.add(new RootReplacement(Pattern.compile(Pattern.quote(trimmed), flags), token, trimmed.length()));
        String forward = trimmed.replace('\\', '/');
        if (!forward.equals(trimmed)) {
            out.add(new RootReplacement(Pattern.compile(Pattern.quote(forward), flags), token, forward.length()));
        }
    }

    private static String getRawUsername() {
        String user = System.getProperty("user.name");
        return user != null ? user.trim() : null;
    }

    private static Pattern getUsernamePattern() {
        if (usernameChecked) {
            return cachedUsernamePattern;
        }
        String user = getRawUsername();
        if (user != null && user.length() >= 3 && !RESERVED_TECH_TOKENS.contains(user.toLowerCase(Locale.ROOT))) {
            cachedUsernamePattern = Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(user) + "(?![A-Za-z0-9_])"
            );
        }
        usernameChecked = true;
        return cachedUsernamePattern;
    }
}
