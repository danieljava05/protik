package com.protik.video.captions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protik.video.captions.WebVtt.InvalidCaptionsException;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebVttTests {

    private static final String GOOD = """
            WEBVTT

            1
            00:00:01.000 --> 00:00:03.500
            Ẹ káàbọ̀ sí fídíò mi

            00:00:04.000 --> 00:00:06.000 align:start position:10%
            Second line
            continues here
            """;

    @Test
    void readsTimedCuesWithIdentifiersSettingsAndMultipleLines() throws Exception {
        List<Cue> cues = WebVtt.parse(GOOD, 10_000);
        assertThat(cues).containsExactly(new Cue(1000, 3500, "Ẹ káàbọ̀ sí fídíò mi"),
                new Cue(4000, 6000, "Second line continues here"));
    }

    @Test
    void acceptsMinutesOnlyTimesCrLfLineEndingsAndABom() throws Exception {
        var cues = WebVtt.parse("﻿WEBVTT - a title\r\n\r\n01:02.003 --> 01:03.000\r\nhi\r\n", null);
        assertThat(cues).containsExactly(new Cue(62_003, 63_000, "hi"));
    }

    @Test
    void ignoresNotesStylesAndRegionsAndRemovesInlineTags() throws Exception {
        var cues = WebVtt.parse("""
                WEBVTT

                NOTE this is a comment
                --> not a cue

                STYLE
                ::cue { color: red }

                00:00:00.000 --> 00:00:01.000
                <v Ade><b>Hello</b></v> <script>alert(1)</script>world
                """, null);
        assertThat(cues).hasSize(1);
        assertThat(cues.getFirst().text()).isEqualTo("Hello alert(1)world");
    }

    @Test
    void whatItWritesIsEscapedSoItCannotCarryMarkup() throws Exception {
        var cues = WebVtt.parse("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\na &lt;b&gt; &amp; c\n", null);
        assertThat(cues.getFirst().text()).isEqualTo("a <b> & c");
        String written = WebVtt.write(cues);
        assertThat(written).contains("a &lt;b&gt; &amp; c").doesNotContain("<b>");
        assertThat(WebVtt.parse(written, null)).isEqualTo(cues);
    }

    @Test
    void writesAndReadsBackTheSameCues() throws Exception {
        var cues = List.of(new Cue(0, 999, "a"), new Cue(3_661_001, 3_662_000, "b"));
        String vtt = WebVtt.write(cues);
        assertThat(vtt).startsWith("WEBVTT\n\n1\n00:00:00.000 --> 00:00:00.999\na\n");
        assertThat(vtt).contains("01:01:01.001 --> 01:01:02.000");
        assertThat(WebVtt.parse(vtt, null)).isEqualTo(cues);
    }

    @Test
    void refusesFilesThatAreNotWebVtt() {
        for (String bad : new String[] {"", "hello", "WEBVTTX\n\n00:00:00.000 --> 00:00:01.000\nx", " WEBVTT\n",
                "1\n00:00:00,000 --> 00:00:01,000\nsrt style", "<html>"}) {
            assertThatThrownBy(() -> WebVtt.parse(bad, null)).as(bad).isInstanceOf(InvalidCaptionsException.class);
        }
        assertThatThrownBy(() -> WebVtt.parse(null, null)).isInstanceOf(InvalidCaptionsException.class);
    }

    @Test
    void refusesBadTimes() {
        for (String timing : new String[] {"00:00:02.000 --> 00:00:01.000", "00:00:01.000 --> 00:00:01.000",
                "00:61:00.000 --> 00:62:00.000", "00:00:61.000 --> 00:01:02.000", "0:0:1.0 --> 0:0:2.0",
                "00:00:01.000 --> banana", "999:59:59.999 --> 999:59:59.999999"}) {
            assertThatThrownBy(() -> WebVtt.parse("WEBVTT\n\n" + timing + "\ntext\n", null)).as(timing)
                    .isInstanceOf(InvalidCaptionsException.class);
        }
    }

    @Test
    void refusesAFileWithoutCuesAndCuesWithoutText() throws Exception {
        assertThatThrownBy(() -> WebVtt.parse("WEBVTT\n\nNOTE only a note\n", null)).isInstanceOf(InvalidCaptionsException.class);
        assertThatThrownBy(() -> WebVtt.parse("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n<b></b>\n", null))
                .isInstanceOf(InvalidCaptionsException.class);
    }

    @Test
    void refusesCuesThatStartAfterTheVideoEnds() throws Exception {
        String vtt = "WEBVTT\n\n00:00:12.000 --> 00:00:13.000\nlate\n";
        assertThatThrownBy(() -> WebVtt.parse(vtt, 10_000)).isInstanceOf(InvalidCaptionsException.class);
        assertThat(WebVtt.parse(vtt, 11_500)).hasSize(1); // within one second of the end is tolerated
        assertThat(WebVtt.parse(vtt, null)).hasSize(1);
    }

    @Test
    void enforcesTheCueAndLengthLimits() {
        String longText = "x".repeat(WebVtt.MAX_CUE_CHARS + 1);
        assertThatThrownBy(() -> WebVtt.parse("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n" + longText + "\n", null))
                .isInstanceOf(InvalidCaptionsException.class);
        StringBuilder many = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i <= WebVtt.MAX_CUES; i++) {
            many.append(WebVtt.format(i * 10)).append(" --> ").append(WebVtt.format(i * 10 + 5)).append("\nx\n\n");
        }
        assertThatThrownBy(() -> WebVtt.parse(many.toString(), null)).isInstanceOf(InvalidCaptionsException.class);
    }

    @Test
    void refusesNulCharacters() {
        assertThatThrownBy(() -> WebVtt.parse("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nbad\u0000text\n", null))
                .isInstanceOf(InvalidCaptionsException.class);
    }
}
