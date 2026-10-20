package com.sixpack.tenant.infra.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Platform-global data: deliberately not tenant-owned. */
@Document("platform_admins")
class PlatformAdminDocument {
    @Id
    String id;
    String name;
    @Indexed(unique = true)
    String email;
    String passwordHash;
}
