package com.sixpack.tenant.application;

import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.shared.tenant.LegalSettings;
import com.sixpack.shared.tenant.MailSettings;
import com.sixpack.shared.tenant.PaymentSettings;
import com.sixpack.shared.tenant.StorefrontSettings;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import com.sixpack.tenant.domain.TenantStatus;
import com.sixpack.user.AdminProvisioning;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Platform-level management of tenants. Every change clears the host cache so it takes effect immediately. */
@Service
public class TenantAdminService {

    private static final Logger log = LoggerFactory.getLogger(TenantAdminService.class);

    static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
    static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern THEME_KEY = Pattern.compile("^--[a-z0-9-]{1,40}$");
    private static final Pattern THEME_VALUE = Pattern.compile("^[#a-zA-Z0-9(),.%\\s-]{1,60}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z._-]{1,32}$");
    private static final Pattern SECRET_REF = Pattern.compile("^[A-Za-z0-9_]{1,40}$");
    private static final Pattern LOGO = Pattern.compile("^(https://[^\\s]{1,190}|uploads/[A-Za-z0-9/_.-]{1,190})$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,100}@[^@\\s]{1,100}\\.[^@\\s]{1,40}$");
    private static final Pattern BASE_URL = Pattern.compile("^https?://[^\\s/$.?#][^\\s]{0,190}$");

    public record AdminCommand(String name, String email, String password) {
    }

    public record CreateCommand(String slug, String name, List<String> domains, Map<String, String> theme,
                                String locale, String currency, LegalSettings legal, PaymentSettings payment,
                                MailSettings mail, StorefrontSettings storefront, Map<String, Boolean> features,
                                AdminCommand admin) {
    }

    /**
     * Null means "keep"; a nested settings object, when given, replaces the previous one. {@code features}
     * replaces the store's feature switches; an empty map returns every feature to the platform default.
     */
    public record UpdateCommand(String name, List<String> domains, Map<String, String> theme, String locale,
                                String currency, LegalSettings legal, PaymentSettings payment, MailSettings mail,
                                StorefrontSettings storefront, Map<String, Boolean> features) {
    }

    private final TenantRepository tenants;
    private final TenantRegistry registry;
    private final TenantProperties props;
    private final AdminProvisioning provisioning;

    TenantAdminService(TenantRepository tenants, TenantRegistry registry, TenantProperties props,
                       AdminProvisioning provisioning) {
        this.tenants = tenants;
        this.registry = registry;
        this.props = props;
        this.provisioning = provisioning;
    }

    public List<Tenant> list() {
        return tenants.findAll();
    }

    public Tenant get(String id) {
        return tenants.findById(id).orElseThrow(() -> ApiException.notFound("Tenant not found"));
    }

    public Tenant create(CreateCommand c) {
        String slug = c.slug() == null ? "" : c.slug().trim();
        if (!SLUG.matcher(slug).matches()) {
            throw ApiException.badRequest("slug: use 1-40 lowercase letters, digits or dashes");
        }
        if (tenants.findBySlug(slug).isPresent()) {
            throw ApiException.conflict("A tenant with this slug already exists");
        }
        String name = requireName(c.name());
        List<String> domains = validDomains(c.domains(), null);
        Map<String, String> theme = validTheme(c.theme());
        LegalSettings legal = validLegal(c.legal());
        PaymentSettings payment = validPayment(c.payment());
        MailSettings mail = validMail(c.mail());
        StorefrontSettings storefront = validStorefront(c.storefront());
        Map<String, Boolean> features = validFeatures(c.features());
        if (c.admin() != null) {
            requireAdmin(c.admin());
        }

        Tenant created = tenants.save(new Tenant(null, slug, name, TenantStatus.ACTIVE, domains, theme,
                blankToDefault(c.locale(), "en-IN"), blankToDefault(c.currency(), "INR"), null, legal, payment, mail, storefront,
                features));
        if (c.admin() != null) {
            try {
                TenantContext.runAs(new TenantRef(created.id(), created.slug()),
                        () -> provisioning.createAdmin(c.admin().name(), c.admin().email(), c.admin().password()));
            } catch (RuntimeException e) {
                tenants.deleteById(created.id()); // do not leave a store nobody can administer
                registry.refresh();
                throw e;
            }
        }
        registry.refresh();
        log.info("Tenant '{}' created with domains {}", created.slug(), created.domains());
        return created;
    }

    public Tenant update(String id, UpdateCommand u) {
        Tenant current = get(id);
        Tenant updated = tenants.save(new Tenant(current.id(), current.slug(),
                u.name() != null ? requireName(u.name()) : current.name(), current.status(),
                u.domains() != null ? validDomains(u.domains(), current.id()) : current.domains(),
                u.theme() != null ? validTheme(u.theme()) : current.theme(),
                u.locale() != null && !u.locale().isBlank() ? u.locale().trim() : current.locale(),
                u.currency() != null && !u.currency().isBlank() ? u.currency().trim() : current.currency(),
                current.createdAt(),
                u.legal() != null ? validLegal(u.legal()) : current.legal(),
                u.payment() != null ? validPayment(u.payment()) : current.payment(),
                u.mail() != null ? validMail(u.mail()) : current.mail(),
                u.storefront() != null ? validStorefront(u.storefront()) : current.storefront(),
                u.features() != null ? validFeatures(u.features()) : current.features()));
        registry.refresh();
        log.info("Tenant '{}' updated", updated.slug());
        return updated;
    }

    /** Known feature keys only, so a typo is an error instead of a switch that silently does nothing. */
    private static Map<String, Boolean> validFeatures(Map<String, Boolean> features) {
        if (features == null) {
            return Map.of();
        }
        Map<String, Boolean> valid = new java.util.TreeMap<>();
        features.forEach((key, on) -> {
            if (Feature.fromKey(key).isEmpty()) {
                throw ApiException.badRequest("features: unknown feature '" + key + "'");
            }
            if (on != null) {
                valid.put(key, on);
            }
        });
        return valid;
    }

    public Tenant suspend(String id) {
        Tenant current = get(id);
        if (current.slug().equals(props.defaultSlug())) {
            throw ApiException.badRequest("The default tenant cannot be suspended");
        }
        return setStatus(current, TenantStatus.SUSPENDED);
    }

    public Tenant activate(String id) {
        return setStatus(get(id), TenantStatus.ACTIVE);
    }

    private Tenant setStatus(Tenant t, TenantStatus status) {
        Tenant saved = tenants.save(new Tenant(t.id(), t.slug(), t.name(), status, t.domains(), t.theme(), t.locale(),
                t.currency(), t.createdAt(), t.legal(), t.payment(), t.mail(), t.storefront(), t.features()));
        registry.refresh();
        log.info("Tenant '{}' is now {}", saved.slug(), status);
        return saved;
    }

    // ---- validation ----------------------------------------------------------------------------------------

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 80) {
            throw ApiException.badRequest("name: required, at most 80 characters");
        }
        return name.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private List<String> validDomains(List<String> raw, String ownId) {
        if (raw == null || raw.isEmpty() || raw.size() > 20) {
            throw ApiException.badRequest("domains: between 1 and 20 are required");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String d : raw) {
            String host = d == null ? "" : d.trim().toLowerCase(Locale.ROOT);
            if (!HOST.matcher(host).matches()) {
                throw ApiException.badRequest("domains: '" + host + "' is not a valid host name");
            }
            unique.add(host);
        }
        List<String> domains = new ArrayList<>(unique);
        for (String host : domains) {
            tenants.findByDomain(host).filter(other -> !other.id().equals(ownId)).ifPresent(other -> {
                throw ApiException.conflict("The domain " + host + " already belongs to another tenant");
            });
        }
        return domains;
    }

    private static Map<String, String> validTheme(Map<String, String> theme) {
        if (theme == null) {
            return Map.of();
        }
        if (theme.size() > 60) {
            throw ApiException.badRequest("theme: at most 60 tokens");
        }
        theme.forEach((key, value) -> {
            if (!THEME_KEY.matcher(key).matches() || value == null || !THEME_VALUE.matcher(value).matches()) {
                throw ApiException.badRequest("theme: '" + key + "' is not a valid design token");
            }
        });
        return theme;
    }

    private static LegalSettings validLegal(LegalSettings legal) {
        if (legal == null) {
            return LegalSettings.NONE;
        }
        for (String version : new String[] {legal.termsVersion(), legal.privacyVersion(), legal.adminAupVersion()}) {
            if (version != null && !VERSION.matcher(version).matches()) {
                throw ApiException.badRequest("legal: versions use letters, digits, '.', '_' or '-' (max 32)");
            }
        }
        return legal;
    }

    private static PaymentSettings validPayment(PaymentSettings payment) {
        if (payment == null) {
            return PaymentSettings.NONE;
        }
        if (payment.provider() != null && !List.of("razorpay", "fake").contains(payment.provider())) {
            throw ApiException.badRequest("payment.provider: razorpay or fake");
        }
        if (payment.secretRef() != null && !SECRET_REF.matcher(payment.secretRef()).matches()) {
            throw ApiException.badRequest("payment.secretRef: letters, digits and '_' only (max 40)");
        }
        if (payment.keyId() != null && payment.keyId().length() > 64) {
            throw ApiException.badRequest("payment.keyId: at most 64 characters");
        }
        return payment;
    }

    private static MailSettings validMail(MailSettings mail) {
        if (mail == null) {
            return MailSettings.NONE;
        }
        if (mail.baseUrl() != null && !BASE_URL.matcher(mail.baseUrl()).matches()) {
            throw ApiException.badRequest("mail.baseUrl: must be an http(s) URL");
        }
        return mail;
    }

    private static StorefrontSettings validStorefront(StorefrontSettings sf) {
        if (sf == null) {
            return StorefrontSettings.NONE;
        }
        if (sf.logoUrl() != null && !LOGO.matcher(sf.logoUrl()).matches()) {
            throw ApiException.badRequest("storefront.logoUrl: an https URL or an uploads/ path");
        }
        for (String email : new String[] {sf.email(), sf.grievanceEmail()}) {
            if (email != null && !EMAIL.matcher(email).matches()) {
                throw ApiException.badRequest("storefront: '" + email + "' is not an email address");
            }
        }
        for (String text : new String[] {sf.legalName(), sf.address(), sf.grievanceOfficer(), sf.phone()}) {
            if (text != null && text.length() > 200) {
                throw ApiException.badRequest("storefront: text fields are limited to 200 characters");
            }
        }
        return sf;
    }

    private static void requireAdmin(AdminCommand admin) {
        if (admin.name() == null || admin.name().isBlank() || admin.name().length() > 80) {
            throw ApiException.badRequest("admin.name: required, at most 80 characters");
        }
        if (admin.email() == null || !admin.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.badRequest("admin.email: must be an email address");
        }
        if (admin.password() == null || admin.password().length() < 12 || admin.password().length() > 72) {
            throw ApiException.badRequest("admin.password: 12 to 72 characters");
        }
    }
}
