package com.protik.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The bitrate ladder and the size estimates derived from it (ADR 0018 decision 4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RenditionTests {

    @Autowired
    RenditionCatalog catalog;

    @Test
    void estimatesAreBitrateTimesDuration() {
        assertThat(new Rendition("144p", 144, 150).estimateBytes(60_000)).isEqualTo(1_125_000L); // 150 kbit/s for a minute
        assertThat(new Rendition("audio", null, 48).estimateBytes(60_000)).isEqualTo(360_000L);
        assertThat(new Rendition("720p", 720, 1800).estimateBytes(15_000)).isEqualTo(3_375_000L);
        assertThat(new Rendition("144p", 144, 150).estimateBytes(0)).isZero();
    }

    @Test
    void theLongerTheVideoTheMoreItCosts() {
        var r = new Rendition("240p", 240, 300);
        long previous = -1;
        for (long ms = 0; ms <= 600_000; ms += 7_500) {
            long bytes = r.estimateBytes(ms);
            assertThat(bytes).isGreaterThanOrEqualTo(previous);
            previous = bytes;
        }
    }

    @Test
    void theCatalogListsTheLadderCheapestFirstIncludingAudioOnly() {
        List<Rendition> all = catalog.all();
        assertThat(all).extracting(Rendition::code).containsExactly("audio", "144p", "240p", "480p", "720p");
        assertThat(all.getFirst().heightPx()).as("audio has no picture").isNull();
        assertThat(all).extracting(Rendition::maxBitrateKbps).isSorted();
    }
}
