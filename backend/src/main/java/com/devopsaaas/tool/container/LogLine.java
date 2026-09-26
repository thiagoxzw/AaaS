package com.devopsaaas.tool.container;

import java.time.Instant;

public record LogLine(Instant timestamp, String stream, String text) {
}
