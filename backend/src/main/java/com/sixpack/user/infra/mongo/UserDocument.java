package com.sixpack.user.infra.mongo;

import java.time.Instant;
import java.util.List;
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
    String phone;
    String locale;
    Boolean emailVerified;
    @Indexed
    String status;
    Instant lastLoginAt;
    List<AddressDocument> addresses;
    @CreatedDate
    Instant createdAt;
    @LastModifiedDate
    Instant updatedAt;

    static class AddressDocument {
        String id;
        String label;
        String name;
        String phone;
        String line1;
        String line2;
        String city;
        String state;
        String country;
        String pinCode;
        boolean isDefault;

        static AddressDocument from(com.sixpack.user.domain.Address a) {
            AddressDocument d = new AddressDocument();
            d.id = a.id();
            d.label = a.label();
            d.name = a.name();
            d.phone = a.phone();
            d.line1 = a.line1();
            d.line2 = a.line2();
            d.city = a.city();
            d.state = a.state();
            d.country = a.country();
            d.pinCode = a.pinCode();
            d.isDefault = a.isDefault();
            return d;
        }

        com.sixpack.user.domain.Address toDomain() {
            return new com.sixpack.user.domain.Address(id, label, name, phone, line1, line2, city, state, country,
                    pinCode, isDefault);
        }
    }
}
