package com.protik.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.protik.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The video module's answer to "does this video exist, and can people see it?" */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class VideoCatalogTests {

    @Autowired
    VideoCatalog catalog;
    @Autowired
    JdbcClient db;

    @AfterEach
    void clean() {
        db.sql("DELETE FROM video.videos WHERE description = 'catalog-test'").update();
    }

    private UUID video(String status) {
        UUID id = UUID.randomUUID();
        db.sql("INSERT INTO video.videos (id, creator_id, description, status, published_at, duration_ms) "
                + "VALUES (:id, :creator, 'catalog-test', :status, CASE WHEN :status = 'PUBLISHED' THEN now() END, "
                + "CASE WHEN :status = 'PUBLISHED' THEN 9000 END)")
                .param("id", id).param("creator", UUID.fromString("00000000-0000-4000-8000-0000000000aa"))
                .param("status", status).update();
        return id;
    }

    @Test
    void aPublishedVideoIsFoundWithItsCreator() {
        UUID id = video("PUBLISHED");
        assertThat(catalog.find(id)).hasValueSatisfying(info -> {
            assertThat(info.videoId()).isEqualTo(id);
            assertThat(info.creatorId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-0000000000aa"));
            assertThat(info.published()).isTrue();
        });
        assertThat(catalog.isPublished(id)).isTrue();
    }

    @Test
    void everyOtherStateExistsButIsNotPublished() {
        for (String status : new String[] {"UPLOADING", "PROCESSING", "FAILED", "TAKEN_DOWN"}) {
            UUID id = video(status);
            assertThat(catalog.find(id)).as(status).isPresent();
            assertThat(catalog.isPublished(id)).as(status).isFalse();
        }
    }

    @Test
    void anUnknownVideoIsNotThereAndNotPublished() {
        UUID id = UUID.randomUUID();
        assertThat(catalog.find(id)).isEmpty();
        assertThat(catalog.isPublished(id)).isFalse();
    }
}
