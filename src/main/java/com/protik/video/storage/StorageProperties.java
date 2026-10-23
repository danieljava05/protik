package com.protik.video.storage;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code protik.storage.*} (ADR 0022). */
@ConfigurationProperties("protik.storage")
public record StorageProperties(
        /* Directory that holds every object. The file-system adapter is for development and one machine. */
        @DefaultValue("./data/objects") Path root) {
}
