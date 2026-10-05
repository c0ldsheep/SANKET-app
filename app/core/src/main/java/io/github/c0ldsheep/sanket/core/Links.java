package io.github.c0ldsheep.sanket.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the link in text that people share from chat apps and browsers. Shared text often wraps
 * the link in a sentence ("Download it here: https://example.com/file.pdf."), so punctuation that
 * ends the sentence is removed, and a closing bracket is kept only when the link opened it.
 */
public final class Links {
    private static final Pattern HTTPS = Pattern.compile("https://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    /** Characters that end a sentence rather than a link, including the Devanagari danda. */
    private static final String TRAILING = ".,;:!?'\"*_~…।॥";
    private static final int MIN_LENGTH = "https://x".length();

    private Links() {}

    /** The first https link in {@code text}, or null when there is none. */
    public static String firstHttps(String text) {
        if (text == null) return null;
        Matcher m = HTTPS.matcher(text);
        if (!m.find()) return null;
        String link = m.group();
        while (!link.isEmpty()) {
            char last = link.charAt(link.length() - 1);
            if (TRAILING.indexOf(last) >= 0 || unmatchedClose(link, last)) {
                link = link.substring(0, link.length() - 1);
            } else {
                break;
            }
        }
        return link.length() >= MIN_LENGTH ? link : null;
    }

    private static boolean unmatchedClose(String s, char last) {
        char open;
        if (last == ')') open = '(';
        else if (last == ']') open = '[';
        else if (last == '}') open = '{';
        else return false;
        return count(s, open) < count(s, last);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}
