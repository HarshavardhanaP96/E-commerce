package com.sixpack.user.domain;

import java.time.Instant;

public record PasswordResetToken(String id, String userId, String tokenHash, Instant expiresAt) {
}
