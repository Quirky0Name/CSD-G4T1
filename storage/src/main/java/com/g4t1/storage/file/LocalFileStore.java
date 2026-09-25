package com.g4t1.storage.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

// PDF bytes live on disk and Postgres only keeps the key. In docker the upload dir
// should be a mounted volume so files survive a container restart.
@Component
public class LocalFileStore {

    private final Path root;

    public LocalFileStore(@Value("${storage.upload-dir}") String uploadDir) throws IOException {
        this.root = Path.of(uploadDir).toAbsolutePath();
        Files.createDirectories(root);
    }

    public String save(byte[] bytes) {
        String key = UUID.randomUUID() + ".pdf";
        try {
            Files.write(root.resolve(key), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store upload " + key, e);
        }
        return key;
    }
}
