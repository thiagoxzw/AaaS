package com.devopsaaas.tool.execution;

import java.util.regex.Pattern;

/**
 * Removes terminal escape sequences and control characters, and makes invisible formatting characters
 * visible (RNF-SEG-11, TM-B5-05). Bidi overrides and zero-width characters are replaced by a visible marker
 * such as {@code <U+202E>} instead of being silently dropped, so a human reviewer can see something was there.
 */
final class OutputSanitizer {

    private static final Pattern ANSI_CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern ANSI_OSC = Pattern.compile("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?");
    private static final Pattern OTHER_ESCAPES = Pattern.compile("\u001B[@-Z\\\\-_]?");
    // C0 controls except tab and newline, DEL and C1 controls.
    private static final Pattern CONTROLS = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F\\x80-\\x9F]");
    // Bidi embeddings/overrides/isolates, zero-width characters, word joiner, BOM, Arabic letter mark.
    private static final Pattern INVISIBLE = Pattern.compile("[\u202A-\u202E\u2066-\u2069\u200B-\u200F\u2060\uFEFF\u061C]");

    private OutputSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String cleaned = ANSI_CSI.matcher(value).replaceAll("");
        cleaned = ANSI_OSC.matcher(cleaned).replaceAll("");
        cleaned = OTHER_ESCAPES.matcher(cleaned).replaceAll("");
        cleaned = CONTROLS.matcher(cleaned).replaceAll("");
        return INVISIBLE.matcher(cleaned)
                .replaceAll(match -> "<U+" + String.format("%04X", (int) match.group().charAt(0)) + ">");
    }
}
