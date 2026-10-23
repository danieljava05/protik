package com.protik.video.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The scanner against a real ClamAV daemon. Runs only when {@code PROTIK_CLAMD_HOST} is set (CI starts a ClamAV service
 * container and sets it); on a machine without ClamAV it is skipped, which means <em>this protocol has not been checked
 * against a real daemon there</em>.
 */
@EnabledIfEnvironmentVariable(named = "PROTIK_CLAMD_HOST", matches = ".+")
class ClamdRealDaemonTests {

    private static ClamdScanner scanner() {
        int port = Integer.parseInt(System.getenv().getOrDefault("PROTIK_CLAMD_PORT", "3310"));
        return new ClamdScanner(System.getenv("PROTIK_CLAMD_HOST"), port, 5000, 120_000);
    }

    /** The industry-standard antivirus test string, assembled at run time so this source file is not itself flagged. */
    private static String eicar() {
        return "X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!" + "$H+H*";
    }

    @Test
    void aHarmlessFileIsClean() throws Exception {
        var result = scanner().scan(new ByteArrayInputStream("just some harmless bytes".getBytes(StandardCharsets.US_ASCII)));
        assertThat(result.clean()).isTrue();
    }

    @Test
    void theStandardTestVirusIsDetected() throws Exception {
        var result = scanner().scan(new ByteArrayInputStream(eicar().getBytes(StandardCharsets.US_ASCII)));
        assertThat(result.clean()).isFalse();
        assertThat(result.signature()).containsIgnoringCase("eicar");
    }

    @Test
    void aLargerFileIsStreamedInChunks() throws Exception {
        byte[] big = new byte[5_000_000];
        var result = scanner().scan(new ByteArrayInputStream(big));
        assertThat(result.clean()).isTrue();
    }
}
