package com.devopsaaas.tool.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RNF-SEG-11, TM-B5-05. */
class OutputSanitizerTest {

    @Test
    void stripsAnsiColorAndCursorSequences() {
        assertThat(OutputSanitizer.sanitize("\u001B[31mERROR\u001B[0m done\u001B[2K\u001B[1A"))
                .isEqualTo("ERROR done");
    }

    @Test
    void stripsOscSequencesSuchAsTerminalTitleChanges() {
        assertThat(OutputSanitizer.sanitize("a\u001B]0;owned\u0007b")).isEqualTo("ab");
    }

    @Test
    void removesControlCharacters_butKeepsTabsAndNewlines() {
        assertThat(OutputSanitizer.sanitize("a\u0000b\u0008c\td\ne\r\u007F")).isEqualTo("abc\td\ne");
    }

    @Test
    void makesInvisibleCharactersVisible_insteadOfSilentlyDroppingThem() {
        assertThat(OutputSanitizer.sanitize("approve‮evil")).isEqualTo("approve<U+202E>evil");
        assertThat(OutputSanitizer.sanitize("zero​width﻿")).isEqualTo("zero<U+200B>width<U+FEFF>");
    }

    @Test
    void leavesOrdinaryTextUntouched() {
        String text = "GET /health 200 in 12ms — ção, 日本語, emoji 🚀";
        assertThat(OutputSanitizer.sanitize(text)).isEqualTo(text);
    }
}
