package com.protik.video.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemObjectStorageTests {

    @TempDir
    Path dir;

    FileSystemObjectStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new FileSystemObjectStorage(dir.resolve("root"));
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storesReadsAndDeletesAnObject() throws IOException {
        assertThat(storage.put("renditions/abc/144p.mp4", bytes("hello"), 100)).isEqualTo(5);
        assertThat(storage.exists("renditions/abc/144p.mp4")).isTrue();
        assertThat(storage.size("renditions/abc/144p.mp4")).isEqualTo(5);
        try (var in = storage.open("renditions/abc/144p.mp4")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        assertThat(storage.delete("renditions/abc/144p.mp4")).isTrue();
        assertThat(storage.exists("renditions/abc/144p.mp4")).isFalse();
        assertThat(storage.delete("renditions/abc/144p.mp4")).as("already gone is not an error").isFalse();
    }

    @Test
    void replacesAnExistingObject() throws IOException {
        storage.put("a/b", bytes("one"), 100);
        storage.put("a/b", bytes("three"), 100);
        assertThat(storage.size("a/b")).isEqualTo(5);
    }

    @Test
    void neverKeepsMoreThanTheLimitAndLeavesNothingBehind() throws IOException {
        assertThatThrownBy(() -> storage.put("big/file", bytes("0123456789"), 9))
                .isInstanceOf(ObjectStorage.TooLargeException.class);
        assertThat(storage.exists("big/file")).isFalse();
        try (Stream<Path> files = Files.walk(dir.resolve("root"))) {
            assertThat(files.filter(Files::isRegularFile)).as("no partial or temporary file").isEmpty();
        }
        assertThat(storage.put("big/file", bytes("012345678"), 9)).as("exactly the limit is fine").isEqualTo(9);
    }

    @Test
    void aFailingStreamLeavesNothingAndKeepsTheOldObject() throws IOException {
        storage.put("keep/me", bytes("original"), 100);
        InputStream broken = new InputStream() {
            int n;

            @Override
            public int read() throws IOException {
                if (n++ > 3) {
                    throw new IOException("connection reset");
                }
                return 'x';
            }
        };
        assertThatThrownBy(() -> storage.put("keep/me", broken, 100)).isInstanceOf(IOException.class);
        try (var in = storage.open("keep/me")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("original");
        }
    }

    @Test
    void keysCannotEscapeTheRootOrHideThemselves() {
        List<String> bad = List.of("../outside", "a/../../outside", "/etc/passwd", "a//b", "a/b/", "", ".hidden", "a/.tmp-x",
                "A/UPPER", "a b", "a\\b", "..", ".", "a/..", "C:/windows", "a\u0000b", "x".repeat(201));
        for (String key : bad) {
            assertThatThrownBy(() -> storage.put(key, bytes("x"), 10)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.open(key)).as(key).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.delete(key)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> storage.put(null, bytes("x"), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(dir.resolve("outside"))).isFalse();
    }

    @Test
    void ordinaryKeysWithDotsDashesAndDigitsAreAccepted() throws IOException {
        storage.put("quarantine/0199-abcd-ef01", bytes("x"), 10);
        storage.put("thumbs/video.v2.jpg", bytes("x"), 10);
        assertThat(storage.exists("thumbs/video.v2.jpg")).isTrue();
    }

    @Test
    void aMissingObjectHasNoLocalPath() {
        assertThatThrownBy(() -> storage.localPath("nothing/here")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void localPathPointsAtTheStoredBytes() throws IOException {
        storage.put("x/y", bytes("data"), 10);
        assertThat(Files.readString(storage.localPath("x/y"))).isEqualTo("data");
    }
}
