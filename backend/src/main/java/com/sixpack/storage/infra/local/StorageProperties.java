package com.sixpack.storage.infra.local;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param localDir directory on disk that backs the public {@code /uploads/**} path
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(String localDir) {

    public StorageProperties {
        if (localDir == null || localDir.isBlank()) {
            localDir = "uploads";
        }
    }
}
