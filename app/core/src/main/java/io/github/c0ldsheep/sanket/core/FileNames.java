package io.github.c0ldsheep.sanket.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chooses a safe, readable name for a downloaded file.
 *
 * <p>The server's own name (the Content-Disposition header) wins over the last part of the link,
 * because links such as {@code download.php?id=7} say nothing about the file. Letters and digits of
 * every script are kept, so Hindi and Marathi names stay readable; path separators and other
 * characters that could misplace or hide the file are replaced.
 */
public final class FileNames {
    static final int MAX_LENGTH = 100;
    private static final String FALLBACK = "download";
    private static final String ALLOWED = " ._-()+,&@'";
    /** Extensions of server scripts, which name the program that sent the file rather than the file. */
    private static final List<String> SCRIPT_EXTENSIONS = List.of("php", "asp", "aspx", "jsp", "cgi", "pl", "py");

    private FileNames() {}

    /**
     * The name to save under. {@code urlSegment} is the decoded last segment of the link;
     * {@code mimeExtension} is the usual extension for the server's content type, or null.
     */
    public static String choose(String disposition, String urlSegment, String mimeExtension) {
        String name = clean(fromDisposition(disposition));
        if (name.isEmpty()) name = clean(urlSegment);
        if (name.isEmpty()) name = FALLBACK;
        boolean hasMimeExt = mimeExtension != null && !mimeExtension.isEmpty();
        String ext = extension(name);
        if (hasMimeExt && ext != null && SCRIPT_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
            name = name.substring(0, name.length() - ext.length() - 1);
            ext = null;
        }
        if (ext == null && hasMimeExt) name = name + "." + mimeExtension.toLowerCase(Locale.ROOT);
        return limit(name);
    }

    /** The file name from a Content-Disposition header (RFC 6266), or null when it has none. */
    public static String fromDisposition(String header) {
        if (header == null) return null;
        String star = null;
        String plain = null;
        for (String part : splitParameters(header)) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(eq + 1).trim();
            if (key.equals("filename*")) {
                int q = value.indexOf("''");
                if (q >= 0) star = percentDecode(unquote(value.substring(q + 2)), charset(value.substring(0, q)));
            } else if (key.equals("filename")) {
                plain = unquote(value);
            }
        }
        String name = star != null && !star.isEmpty() ? star : plain;
        return name == null || name.isEmpty() ? null : name;
    }

    /** A cleaned-up name, or "" when nothing usable is left. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean keep = Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK || ALLOWED.indexOf(cp) >= 0;
            b.appendCodePoint(keep ? cp : '_');
        }
        String t = b.toString().replaceAll("_{2,}", "_").replaceAll(" {2,}", " ").trim();
        t = t.replaceAll("^[. _]+", "").replaceAll("[. ]+$", "");
        return t.isEmpty() ? "" : limit(t);
    }

    /** The extension without the dot, or null when the name has none. */
    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1);
        if (ext.length() > 10) return null;
        for (int i = 0; i < ext.length(); i++) {
            if (!Character.isLetterOrDigit(ext.charAt(i))) return null;
        }
        return ext;
    }

    /** Shortens a name to {@link #MAX_LENGTH} characters, keeping its extension and whole characters. */
    static String limit(String name) {
        if (name.codePointCount(0, name.length()) <= MAX_LENGTH) return name;
        String ext = extension(name);
        String tail = ext == null ? "" : "." + ext;
        String base = ext == null ? name : name.substring(0, name.length() - tail.length());
        int keep = Math.max(1, MAX_LENGTH - tail.codePointCount(0, tail.length()));
        int end = base.offsetByCodePoints(0, Math.min(keep, base.codePointCount(0, base.length())));
        return base.substring(0, end).trim() + tail;
    }

    private static List<String> splitParameters(String header) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '\\' && quoted && i + 1 < header.length()) {
                cur.append(c).append(header.charAt(++i));
            } else if (c == '"') {
                quoted = !quoted;
                cur.append(c);
            } else if (c == ';' && !quoted) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    private static String unquote(String v) {
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') return v;
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length() - 1) c = v.charAt(++i);
            b.append(c);
        }
        return b.toString();
    }

    private static Charset charset(String name) {
        return name.trim().equalsIgnoreCase("iso-8859-1") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
    }

    /** Decodes %XX sequences; anything malformed is kept as it is. */
    static String percentDecode(String s, Charset cs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        byte[] raw = s.getBytes(cs);
        for (int i = 0; i < raw.length; i++) {
            byte c = raw[i];
            if (c == '%' && i + 2 < raw.length) {
                int hi = Character.digit(raw[i + 1], 16);
                int lo = Character.digit(raw[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out.write(c);
        }
        return new String(out.toByteArray(), cs);
    }
}
