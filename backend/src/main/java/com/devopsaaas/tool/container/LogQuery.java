package com.devopsaaas.tool.container;

import java.time.Duration;

/** Bounded log read: tail lines and an optional look-back window. */
public record LogQuery(int tail, Duration since) {
}
