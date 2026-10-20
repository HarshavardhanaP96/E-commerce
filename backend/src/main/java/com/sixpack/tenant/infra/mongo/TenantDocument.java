package com.sixpack.tenant.infra.mongo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("tenants")
class TenantDocument {
    @Id
    String id;
    @Indexed(unique = true)
    String slug;
    String name;
    String status;
    /** Multikey unique index: a host belongs to at most one tenant. */
    @Indexed(unique = true, sparse = true)
    List<String> domains;
    Map<String, String> theme;
    String locale;
    String currency;
    LegalDoc legal;
    PaymentDoc payment;
    MailDoc mail;
    StorefrontDoc storefront;
    Map<String, Boolean> features;

    static class LegalDoc {
        String termsVersion;
        String privacyVersion;
        String adminAupVersion;
    }

    static class PaymentDoc {
        String provider;
        String keyId;
        String secretRef;
    }

    static class StorefrontDoc {
        String logoUrl;
        String legalName;
        String address;
        String email;
        String phone;
        String grievanceOfficer;
        String grievanceEmail;
    }

    static class MailDoc {
        String baseUrl;
    }
    @CreatedDate
    Instant createdAt;
}
