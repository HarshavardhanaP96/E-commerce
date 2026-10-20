package com.sixpack.user.infra.mongo;

import com.sixpack.shared.tenant.TenantDocument;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("password_reset_tokens")
@CompoundIndex(name = "tenant_token_hash", def = "{'tenantId': 1, 'tokenHash': 1}", unique = true)
class PasswordResetTokenDocument extends TenantDocument {

    @Id
    String id;
    @Indexed
    String userId;
    String tokenHash;
    @Indexed(expireAfter = "0s")
    Instant expiresAt;
}
