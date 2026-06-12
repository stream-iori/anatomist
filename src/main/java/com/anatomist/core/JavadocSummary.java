package com.anatomist.core;

public final class JavadocSummary {

    private JavadocSummary() {}

    public static String extract(String rawJavadoc) {
        if (rawJavadoc == null || rawJavadoc.isBlank()) return null;

        String body = stripTags(rawJavadoc);
        if (body.isEmpty()) return null;

        String summary = extractSummaryFragment(body);
        return summary.isBlank() ? null : summary;
    }

    static String stripTags(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String trimmed = line.strip();
            // strip leading * from javadoc comment lines
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1).stripLeading();
            }
            // stop at first @tag line
            if (trimmed.startsWith("@")) break;
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(trimmed);
            }
        }
        return sb.toString().strip();
    }

    static String extractSummaryFragment(String body) {
        // Java standard summary fragment: first sentence ending with . or 。
        // followed by whitespace, end-of-string, or next @tag
        int dotPos = findSentenceEnd(body);
        if (dotPos >= 0) {
            return body.substring(0, dotPos + 1).strip();
        }
        // no sentence terminator found — return full body
        return body.strip();
    }

    private static int findSentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。') {
                // Chinese period is always a sentence terminator
                return i;
            }
            if (c == '.') {
                // Latin period: must be followed by whitespace, end-of-string, or HTML tag
                if (i + 1 >= text.length()) return i;
                char next = text.charAt(i + 1);
                if (Character.isWhitespace(next) || next == '<') return i;
            }
        }
        return -1;
    }
}
