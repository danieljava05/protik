package com.protik.video.captions;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the stand-in transcriber until a real model is configured (ADR 0023). */
@Configuration(proxyBeanMethods = false)
class CaptionConfiguration {

    @Bean
    @ConditionalOnMissingBean(Transcriber.class)
    Transcriber noTranscriber() {
        return new Transcriber() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String model() {
                return "none";
            }

            @Override
            public String modelVersion() {
                return "none";
            }

            @Override
            public Transcript transcribe(Path audio, String language) throws IOException {
                throw new IOException("No speech recognition model is configured");
            }
        };
    }
}
