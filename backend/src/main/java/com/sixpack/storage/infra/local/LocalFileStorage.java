package com.sixpack.storage.infra.local;

import com.sixpack.shared.ApiException;
import com.sixpack.storage.FileStorage;
import com.sixpack.storage.ImageType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LocalFileStorage implements FileStorage {

    static final String PUBLIC_PREFIX = "uploads/";
    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    LocalFileStorage(StorageProperties props) {
        this.root = Path.of(props.localDir()).toAbsolutePath().normalize();
    }

    @Override
    public String storeImage(byte[] content) {
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("photo: is required");
        }
        if (content.length > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest("photo: file too large (max 5 MB)");
        }
        ImageType type = ImageType.detect(content)
                .orElseThrow(() -> ApiException.badRequest("photo: only JPEG, PNG, GIF or WEBP images are allowed"));
        LocalDate today = LocalDate.now();
        String relative = "%d/%02d/%02d/%s.%s".formatted(today.getYear(), today.getMonthValue(),
                today.getDayOfMonth(), UUID.randomUUID(), type.extension());
        Path target = root.resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store upload", e);
        }
        return PUBLIC_PREFIX + relative;
    }

    @Override
    public void delete(String path) {
        if (path == null || !path.startsWith(PUBLIC_PREFIX)) {
            return; // external URL or legacy value we did not create
        }
        Path target = root.resolve(path.substring(PUBLIC_PREFIX.length())).normalize();
        if (!target.startsWith(root)) {
            log.warn("Refusing to delete path outside storage root: {}", path);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not delete {}", path, e);
        }
    }

    Path root() {
        return root;
    }
}
