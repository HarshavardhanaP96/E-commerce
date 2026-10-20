package com.sixpack.user.domain;

import java.time.Instant;

public record EmailVerificationToken(String id, String userId, String tokenHash, Instant expiresAt) {
}
