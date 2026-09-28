package com.devopsaaas.tool.container;

import java.time.Instant;

/**
 * Bounded log read: the most recent {@code tail} lines, optionally only those at or after {@code since}. The
 * instant keeps its nanoseconds: a container restarted within the same second would otherwise leak lines of
 * its previous run into the current one (verified on Docker 29.3.1, slice 6.1).
 */
public record LogQuery(int tail, Instant since) {
}
