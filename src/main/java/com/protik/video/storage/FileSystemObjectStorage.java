package com.protik.video.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Objects as files under one root. Keys are checked against a whitelist and the resolved path against the root, so a key
 * cannot climb out of it. Writes go to a temporary file and are moved into place, so a reader never sees half an object.
 */
public class FileSystemObjectStorage implements ObjectStorage {

    /** Lower-case letters, digits, dot, dash, underscore, and slashes between non-empty parts. No "..". */
    private static final Pattern KEY = Pattern.compile("[a-z0-9_-]+([.][a-z0-9_-]+)*(/[a-z0-9_-]+([.][a-z0-9_-]+)*)*");
    private static final int MAX_KEY_LENGTH = 200;

    private final Path root;

    public FileSystemObjectStorage(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public long put(String key, InputStream content, long maxBytes) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(".tmp-" + UUID.randomUUID());
        long total = 0;
        try (var out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = content.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new TooLargeException(maxBytes);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        return total;
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public long size(String key) throws IOException {
        return Files.size(resolve(key));
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public boolean delete(String key) throws IOException {
        return Files.deleteIfExists(resolve(key));
    }

    @Override
    public Path localPath(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(key);
        }
        return path;
    }

    private Path resolve(String key) {
        if (key == null || key.length() > MAX_KEY_LENGTH || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
