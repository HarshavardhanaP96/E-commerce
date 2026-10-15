package com.sixpack.user.domain;

import java.util.Optional;

public interface PasswordResetTokenRepository {

    void save(PasswordResetToken token);

    /** Atomically removes and returns the token so it can be used only once. */
    Optional<PasswordResetToken> consumeByHash(String tokenHash);

    void deleteByUserId(String userId);
}
