package com.sixpack.tenant.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param adminEmail    optional first platform administrator, created at startup if missing
 * @param adminPassword at least 12 characters
 */
@ConfigurationProperties("app.platform")
public record PlatformProperties(String adminEmail, String adminPassword) {
}
