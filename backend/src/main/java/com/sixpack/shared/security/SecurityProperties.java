package com.sixpack.shared.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @NotBlank(message = "JWT_SECRET must be set") String jwtSecret,
        Duration jwtTtl,
        List<String> corsAllowedOrigins,
        @Positive int authRequestsPerMinute) {

    public SecurityProperties {
        if (jwtSecret != null && !jwtSecret.isBlank()
                && jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must be at least 32 bytes");
        }
        if (jwtTtl == null) {
            jwtTtl = Duration.ofHours(24);
        }
        if (corsAllowedOrigins == null) {
            corsAllowedOrigins = List.of();
        }
    }
}
