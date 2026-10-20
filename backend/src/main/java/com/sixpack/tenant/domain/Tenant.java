package com.sixpack.tenant.domain;

import com.sixpack.shared.tenant.LegalSettings;
import com.sixpack.shared.tenant.MailSettings;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.StoreSettings;
import com.sixpack.shared.tenant.StorefrontSettings;
import com.sixpack.shared.tenant.TenantLimits;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A store. {@code theme} maps CSS design-token names to values for the storefront (ADR-013). {@code features}
 * holds the store's own feature switches by {@link com.sixpack.shared.tenant.Feature} key; a missing key means
 * "use the platform default".
 */
public record Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                     Map<String, String> theme, String locale, String currency, Instant createdAt,
                     LegalSettings legal, PaymentSettings payment, MailSettings mail,
                     StorefrontSettings storefront, Map<String, Boolean> features, Instant deletionDueAt,
                     TenantLimits limits, Instant trialEndsAt, StoreSettings settings) {

    public Tenant {
        status = status == null ? TenantStatus.ACTIVE : status;
        domains = domains == null ? List.of() : List.copyOf(domains);
        theme = theme == null ? Map.of() : Map.copyOf(theme);
        legal = legal == null ? LegalSettings.NONE : legal;
        payment = payment == null ? PaymentSettings.NONE : payment;
        mail = mail == null ? MailSettings.NONE : mail;
        storefront = storefront == null ? StorefrontSettings.NONE : storefront;
        features = features == null ? Map.of() : Map.copyOf(features);
        limits = limits == null ? TenantLimits.NONE : limits;
        trialEndsAt = status == TenantStatus.TRIAL ? trialEndsAt : null;
        settings = settings == null ? StoreSettings.NONE : settings;
    }

    /** Without the owner's shop rules. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail,
                  StorefrontSettings storefront, Map<String, Boolean> features, Instant deletionDueAt,
                  TenantLimits limits, Instant trialEndsAt) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, storefront,
                features, deletionDueAt, limits, trialEndsAt, null);
    }

    /** The same store with the owner's shop rules and storefront details. */
    public Tenant withOwnerSettings(StoreSettings newSettings, StorefrontSettings newStorefront) {
        return new Tenant(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail,
                newStorefront, features, deletionDueAt, limits, trialEndsAt, newSettings);
    }

    /** Not on trial. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail,
                  StorefrontSettings storefront, Map<String, Boolean> features, Instant deletionDueAt,
                  TenantLimits limits) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, storefront,
                features, deletionDueAt, limits, null);
    }

    /** With the platform's default limits. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail,
                  StorefrontSettings storefront, Map<String, Boolean> features, Instant deletionDueAt) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, storefront,
                features, deletionDueAt, null);
    }

    /** Not scheduled for deletion. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail,
                  StorefrontSettings storefront, Map<String, Boolean> features) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, storefront,
                features, null);
    }

    /**
     * The same store in another status; {@code deletionDueAt} is only kept while scheduled for deletion, and the
     * trial end only while still on trial.
     */
    public Tenant withStatus(TenantStatus newStatus, Instant newDeletionDueAt) {
        return new Tenant(id, slug, name, newStatus, newStatus == TenantStatus.DELETED ? List.of() : domains, theme,
                locale, currency, createdAt, legal, payment, mail, storefront, features, newDeletionDueAt, limits,
                trialEndsAt, settings);
    }

    /** Without feature switches. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail,
                  StorefrontSettings storefront) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, storefront,
                null);
    }

    /** Without storefront details. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt,
                  LegalSettings legal, PaymentSettings payment, MailSettings mail) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, legal, payment, mail, null, null);
    }

    /** Without per-tenant settings: everything falls back to the platform defaults. */
    public Tenant(String id, String slug, String name, TenantStatus status, List<String> domains,
                  Map<String, String> theme, String locale, String currency, Instant createdAt) {
        this(id, slug, name, status, domains, theme, locale, currency, createdAt, null, null, null, null, null);
    }
}
