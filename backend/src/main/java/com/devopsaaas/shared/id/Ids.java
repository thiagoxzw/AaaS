package com.devopsaaas.shared.id;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Generates UUIDv7 identifiers (RFC 9562) inside the application, so an entity has its identity before it
 * is persisted and the domain does not depend on the ORM or the database to create ids.
 *
 * <p>Layout: 48-bit Unix epoch milliseconds, version 7, a 12-bit counter that keeps ids monotonic within
 * the same millisecond (RFC 9562, "method 1"), the IETF variant and 62 random bits.
 */
public final class Ids {

    private static final Ids DEFAULT = new Ids(System::currentTimeMillis, new SecureRandom());
    private static final int MAX_COUNTER = 0xFFF;

    private final LongSupplier clock;
    private final SecureRandom random;
    private long lastMillis = -1;
    private int counter;

    Ids(LongSupplier clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public static UUID newId() {
        return DEFAULT.next();
    }

    synchronized UUID next() {
        long now = clock.getAsLong();
        if (now > lastMillis) {
            lastMillis = now;
            // Start low in the counter space so several ids per millisecond still fit.
            counter = random.nextInt(MAX_COUNTER / 2);
        } else if (++counter > MAX_COUNTER) {
            // Counter exhausted (or clock moved backwards): borrow the next millisecond to stay monotonic.
            lastMillis++;
            counter = 0;
        }
        long mostSignificant = (lastMillis << 16) | (0x7L << 12) | counter;
        long leastSignificant = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
