package com.devopsaaas.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdsTest {

    private static final Comparator<UUID> UNSIGNED_ORDER = Comparator
            .comparing((UUID id) -> id.getMostSignificantBits(), Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    @Test
    void newId_isVersion7WithIetfVariant() {
        UUID id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void newId_startsWithTheCurrentUnixMillis() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();

        long embeddedMillis = id.getMostSignificantBits() >>> 16;
        assertThat(embeddedMillis).isBetween(before, after + 1);
    }

    @Test
    void ids_areMonotonic_withinTheSameMillisecond() {
        Ids ids = new Ids(() -> 1_700_000_000_000L, new SecureRandom());

        List<UUID> generated = generate(ids, 3000);

        assertThat(generated).isSortedAccordingTo(UNSIGNED_ORDER).doesNotHaveDuplicates();
    }

    @Test
    void ids_stayMonotonic_whenTheClockMovesBackwards() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        Ids ids = new Ids(clock::get, new SecureRandom());

        UUID first = ids.next();
        clock.set(1_699_999_999_000L);
        UUID second = ids.next();

        assertThat(UNSIGNED_ORDER.compare(first, second)).isNegative();
    }

    private static List<UUID> generate(Ids ids, int count) {
        List<UUID> generated = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            generated.add(ids.next());
        }
        return generated;
    }
}
