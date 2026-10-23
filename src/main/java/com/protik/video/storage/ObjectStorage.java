package com.protik.video.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Where video files live (ADR 0022 decision 1). A port, so a MinIO/S3 adapter can replace the file-system one
 * without touching the pipeline.
 */
public interface ObjectStorage {

    /** Thrown by {@link #put} when the stream is longer than the allowed size. Nothing is kept. */
    class TooLargeException extends IOException {
        public TooLargeException(long limit) {
            super("The content is larger than " + limit + " bytes");
        }
    }

    /**
     * Stores the stream under the key, replacing any earlier object, and never keeps more than {@code maxBytes}.
     *
     * @return the number of bytes stored
     * @throws TooLargeException if the stream is longer; the partial object is removed
     */
    long put(String key, InputStream content, long maxBytes) throws IOException;

    /** Opens an object for reading. @throws java.nio.file.NoSuchFileException if there is none */
    InputStream open(String key) throws IOException;

    long size(String key) throws IOException;

    boolean exists(String key);

    /** Deletes an object. @return false if it was already gone (not an error) */
    boolean delete(String key) throws IOException;

    /** A local file holding the object, for tools that need a path. Valid until the process ends or the object changes. */
    Path localPath(String key) throws IOException;
}
