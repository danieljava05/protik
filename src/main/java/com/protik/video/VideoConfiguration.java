package com.protik.video;

import com.protik.video.processing.ClamdScanner;
import com.protik.video.processing.MalwareScanner;
import com.protik.video.processing.MediaTools;
import com.protik.video.processing.ProcessingProperties;
import com.protik.video.processing.UnscannedScanner;
import com.protik.video.service.UploadProperties;
import com.protik.video.storage.FileSystemObjectStorage;
import com.protik.video.storage.ObjectStorage;
import com.protik.video.storage.StorageProperties;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires uploads and processing (ADR 0022). Each adapter can be replaced by defining a bean of the port type. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({StorageProperties.class, UploadProperties.class, ProcessingProperties.class})
class VideoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    ObjectStorage objectStorage(StorageProperties props) throws IOException {
        return new FileSystemObjectStorage(props.root());
    }

    @Bean
    @ConditionalOnMissingBean(MalwareScanner.class)
    MalwareScanner malwareScanner(ProcessingProperties props) {
        var scan = props.scan();
        if (scan.mode().equals("disabled")) {
            return new UnscannedScanner();
        }
        return new ClamdScanner(scan.host(), scan.port(), (int) scan.connectTimeout().toMillis(),
                (int) scan.readTimeout().toMillis());
    }

    @Bean
    MediaTools mediaTools(ProcessingProperties props, JsonMapper json) {
        return new MediaTools(props.ffmpeg(), json);
    }
}
