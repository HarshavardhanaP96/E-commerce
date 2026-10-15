package com.sixpack.user.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param frontendUrl  base URL used to build password-reset links
 * @param mailLogLinks dev only: log reset links instead of mailing them
 * @param adminEmail   optional bootstrap admin, created at startup if missing
 * @param adminPassword password for the bootstrap admin
 */
@ConfigurationProperties("app.user")
public record UserProperties(String frontendUrl, boolean mailLogLinks, String adminEmail, String adminPassword) {
}
