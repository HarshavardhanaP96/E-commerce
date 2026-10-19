package com.sixpack.tenant.domain;

import com.sixpack.shared.tenant.LegalSettings;
import com.sixpack.shared.tenant.MailSettings;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.StorefrontSettings;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A store. {@code theme} maps CSS design-token names to values for the storefront (ADR-013).
 */
public record Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                     Map<String, String> theme, String locale, String currency, Instant createdAt,
                     LegalSettings legal, PaymentSettings payment, MailSettings mail,
                     StorefrontSettings storefront) {

    public Tenant {
        status = status == null ? TenantStatus.ACTIVE : status;
        domains = domains == null ? List.of() : List.copyOf(domains);
        theme = theme == null ? Map.of() : Map.copyOf(theme);
        legal = legal == null ? LegalSettings.NONE : legal;
        payment = payment == null ? PaymentSettings.NONE : payment;
        mail = mail == null ? MailSettings.NONE : mail;
        storefront = storefront == null ? StorefrontSettings.NONE : storefront;
    }

    /** Without storefront details. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, null);
    }

    /** Without per-tenant settings: everything falls back to the platform defaults. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, null, null, null, null);
    }
}
