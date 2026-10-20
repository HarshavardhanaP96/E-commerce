package com.sixpack.storage.infra.local;

import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.storage.FileStorage;
import com.sixpack.storage.ImageType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
        String tenantFolder = TenantContext.current().map(t -> t.id() + "/").orElse("");
        String relative = "%s%d/%02d/%02d/%s.%s".formatted(tenantFolder, today.getYear(), today.getMonthValue(),
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
        String relative = path.substring(PUBLIC_PREFIX.length());
        if (belongsToAnotherTenant(relative)) {
            log.warn("Refusing to delete another tenant's file: {}", path);
            return;
        }
        Path target = root.resolve(relative).normalize();
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

    /** Tenant uploads live under {@code <tenantId>/…}; legacy files start with the year. */
    private static boolean belongsToAnotherTenant(String relative) {
        int slash = relative.indexOf('/');
        String first = slash < 0 ? relative : relative.substring(0, slash);
        if (!first.matches("[0-9a-f]{24}")) {
            return false;
        }
        return TenantContext.current().map(t -> !t.id().equals(first)).orElse(false);
    }

    @Override
    public List<String> tenantFiles() {
        Path dir = tenantDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> PUBLIC_PREFIX + root.relativize(f).toString().replace('\\', '/'))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list uploads", e);
        }
    }

    @Override
    public InputStream open(String path) {
        Path dir = tenantDir();
        Path target = path == null || !path.startsWith(PUBLIC_PREFIX) ? null
                : root.resolve(path.substring(PUBLIC_PREFIX.length())).normalize();
        if (target == null || !target.startsWith(dir)) {
            throw new IllegalArgumentException("Not a file of this tenant: " + path);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    @Override
    public void deleteTenantFiles() {
        Path dir = tenantDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete uploads of tenant", e);
        }
    }

    /** {@code <root>/<tenantId>}; requires a tenant so a purge can never reach the shared root. */
    private Path tenantDir() {
        return root.resolve(TenantContext.require().id()).normalize();
    }

    Path root() {
        return root;
    }
}
