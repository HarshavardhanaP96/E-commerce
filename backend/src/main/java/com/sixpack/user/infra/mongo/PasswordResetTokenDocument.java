package com.sixpack.user.infra.mongo;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("password_reset_tokens")
class PasswordResetTokenDocument {

    @Id
    String id;
    @Indexed
    String userId;
    @Indexed(unique = true)
    String tokenHash;
    @Indexed(expireAfter = "0s")
    Instant expiresAt;
}
