package com.protik.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTests {

    @Test
    void producesVersion7Rfc9562Uuids() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTheTimestampSoIdsSortByCreationTime() {
        UUID earlier = Ids.newId(1_700_000_000_000L);
        UUID later = Ids.newId(1_700_000_000_001L);
        assertThat(earlier.getMostSignificantBits() >>> 16).isEqualTo(1_700_000_000_000L);
        // lexicographic (database) order follows time, regardless of the random bits
        assertThat(earlier.toString().compareTo(later.toString())).isNegative();
    }

    @Test
    void idsDoNotCollide() {
        var seen = new HashSet<UUID>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(Ids.newId())).isTrue();
        }
    }
}
