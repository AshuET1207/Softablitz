package com.slingring.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an untrusted file name from a remote sender into one that is safe to
 * create on the local disk, and finds a destination that will not overwrite
 * an existing file.
 *
 * Defends against: path traversal ("../../x", "C:\\Windows\\x"), control
 * characters, characters illegal on Windows, Windows reserved device names,
 * trailing dots/spaces, and over-long names.
 */
public final class FileNames {

    private static final String FALLBACK = "received-file";
    private static final int MAX_BYTES = 200; // leaves room for " (99)" and temp suffixes under the usual 255 limit

    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNames() { }

    /** Returns a safe, plain file name (never a path). Never returns null or empty. */
    public static String sanitize(String raw) {
        if (raw == null) {
            return FALLBACK;
        }

        // 1. Keep only the name of file
        String name = raw;
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }

        // 2. Replace control characters and characters illegal on common filesystems.
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bad = c < 0x20 || c == 0x7F || "<>:\"|?*".indexOf(c) >= 0;
            sb.append(bad ? '_' : c);
        }
        name = sb.toString();

        // 3. Windows silently strips trailing dots and spaces; also drop leading spaces.
        name = stripEdges(name);

        // 4. Names made only of dots ("." / "..") are never valid.
        if (name.isEmpty() || name.chars().allMatch(ch -> ch == '.')) {
            return FALLBACK;
        }

        // 5. Reserved Windows device names
        String stem = stem(name);
        if (RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            name = "_" + name;
        }

        // 6. Limit length, keeping the extension so the file still opens correctly.
        name = truncate(name);

        return name.isEmpty() ? FALLBACK : name;
    }

    /**
     * Resolve a sanitized name inside a directory and confirm the result really
     * stays inside it. Throws if it does not (defense in depth after sanitize).
     */
    public static Path resolveInside(Path dir, String safeName) {
        Path base = dir.toAbsolutePath().normalize();
        Path target = base.resolve(safeName).normalize();
        if (!target.startsWith(base) || !base.equals(target.getParent())) {
            throw new SecurityException("Refusing path outside save folder: " + safeName);
        }
        return target;
    }

    /**
     * First free path in dir for this name: "photo.jpg", then "photo (1).jpg", "photo (2).jpg"...
     * Call right before moving the file into place. Use Files.move without
     * REPLACE_EXISTING so a race cannot silently overwrite anything.
     */
    public static Path uniqueIn(Path dir, String safeName) {
        Path candidate = resolveInside(dir, safeName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String stem = stem(safeName);
        String ext = extension(safeName); // includes the dot, or ""
        for (int i = 1; i < 10_000; i++) {
            candidate = resolveInside(dir, stem + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Too many files named " + safeName);
    }

    // ---- helpers ----

    private static String stripEdges(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == ' ') start++;
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
        return s.substring(start, end);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static String truncate(String name) {
        if (name.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
            return name;
        }
        String ext = extension(name);
        if (ext.getBytes(StandardCharsets.UTF_8).length > 20) {
            ext = ""; // absurd extension: drop it rather than lose the whole stem
        }
        String stem = ext.isEmpty() ? name : stem(name);
        int budget = MAX_BYTES - ext.getBytes(StandardCharsets.UTF_8).length;

        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < stem.length(); ) {
            int cp = stem.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + len > budget) break;
            out.appendCodePoint(cp);
            used += len;
            i += Character.charCount(cp);
        }
        return stripEdges(out.toString()) + ext;
    }
}