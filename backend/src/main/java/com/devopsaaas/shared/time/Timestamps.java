package com.devopsaaas.shared.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Timestamps at the precision PostgreSQL stores ({@code timestamptz} keeps microseconds). */
public final class Timestamps {

    private Timestamps() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
