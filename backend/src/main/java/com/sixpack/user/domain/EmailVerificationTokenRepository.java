package com.sixpack.user.domain;

import java.util.Optional;

public interface EmailVerificationTokenRepository {

    void save(EmailVerificationToken token);

    /** Atomically removes and returns the token so it can be used only once. */
    Optional<EmailVerificationToken> consumeByHash(String tokenHash);

    void deleteByUserId(String userId);
}
