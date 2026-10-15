package com.sixpack.storage;

/**
 * Public API of the storage module. Implementations: local disk (default). An S3-compatible adapter can
 * be added without touching callers (ADR-008).
 */
public interface FileStorage {

    long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /**
     * Validates the bytes are a supported image (by magic number, not client metadata) and stores them.
     *
     * @return the public path to persist, e.g. {@code uploads/2026/10/14/<uuid>.png}
     * @throws com.sixpack.shared.ApiException 400 if the content is not a supported image or too large
     */
    String storeImage(byte[] content);

    /** Deletes a previously stored file. Absolute URLs and paths outside the storage root are ignored. */
    void delete(String path);
}
