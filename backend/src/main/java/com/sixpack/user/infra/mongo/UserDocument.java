package com.sixpack.user.infra.mongo;

import java.time.Instant;
import java.util.Date;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Mirrors the legacy Mongoose `users` schema so existing documents load unchanged. */
@Document("users")
class UserDocument {

    @Id
    String id;
    String name;
    @Indexed(unique = true)
    String email;
    String password;
    String photo;
    String role;
    String gender;
    Date dob;
    @CreatedDate
    Instant createdAt;
    @LastModifiedDate
    Instant updatedAt;
}
