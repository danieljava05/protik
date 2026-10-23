package com.protik.video.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.video.service.ContainerSniffer.Container;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContainerSnifferTests {

    private static byte[] mp4() {
        byte[] head = new byte[12];
        head[3] = 0x20;
        System.arraycopy("ftypisom".getBytes(StandardCharsets.US_ASCII), 0, head, 4, 8);
        return head;
    }

    @Test
    void recognisesMp4AndMovByTheirFtypBox() {
        assertThat(ContainerSniffer.sniff(mp4(), 12)).isEqualTo(Container.ISO_BMFF);
    }

    @Test
    void recognisesWebmByItsEbmlHeader() {
        byte[] head = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(ContainerSniffer.sniff(head, 12)).isEqualTo(Container.MATROSKA);
    }

    @Test
    void refusesEverythingElse() {
        assertThat(ContainerSniffer.sniff("MZ\u0090\u0000\u0003\u0000\u0000\u0000\u0004\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1), 12))
                .as("a Windows executable").isNull();
        assertThat(ContainerSniffer.sniff("<?xml version=".getBytes(StandardCharsets.US_ASCII), 12)).isNull();
        assertThat(ContainerSniffer.sniff("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1), 12)).isNull();
        assertThat(ContainerSniffer.sniff(new byte[12], 12)).isNull();
    }

    @Test
    void aTooShortHeadIsNotRecognised() {
        assertThat(ContainerSniffer.sniff(mp4(), 11)).isNull();
        assertThat(ContainerSniffer.sniff(new byte[12], 0)).isNull();
        assertThat(ContainerSniffer.sniff(new byte[] {0x1A, 0x45, (byte) 0xDF, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 3)).isNull();
    }

    @Test
    void declaredTypesMapToContainersAndIgnoreParametersAndCase() {
        assertThat(ContainerSniffer.declared("video/mp4")).isEqualTo(Container.ISO_BMFF);
        assertThat(ContainerSniffer.declared("VIDEO/QuickTime")).isEqualTo(Container.ISO_BMFF);
        assertThat(ContainerSniffer.declared("video/webm; codecs=vp9")).isEqualTo(Container.MATROSKA);
        for (String bad : new String[] {null, "", "application/octet-stream", "text/html", "image/png", "video/x-msvideo",
                "video/mp4x", "multipart/form-data"}) {
            assertThat(ContainerSniffer.declared(bad)).as(String.valueOf(bad)).isNull();
        }
    }
}
