package com.sixpack.tenant.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.StoreSettings;
import com.sixpack.shared.tenant.StorefrontSettings;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantRepository;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The store owner's own shop rules and storefront details, for the store in {@link TenantContext}. Each change is
 * recorded in the store's audit log; all of it is store configuration, none of it personal data.
 */
@Service
public class StoreSettingsService {

    /** The groups to replace; a null group is left as it is. */
    public record Command(StoreSettings.Commerce commerce, StoreSettings.Catalog catalog, StoreSettings.Invoice invoice,
                          StoreSettings.Regional regional, StorefrontSettings storefront) {
    }

    /** @param defaults what each rule returns to when the store clears its own value */
    public record View(StoreSettings settings, StorefrontSettings storefront, StoreConfig.Effective effective,
                       StoreConfig.Effective defaults) {
    }

    private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final List<String> FIELDS = List.of("commerce.shippingFee", "commerce.freeShippingAbove",
            "commerce.defaultTaxRate", "commerce.maxCouponAmount", "commerce.minimumAge", "commerce.stuckOrderDays",
            "catalog.pageSize", "catalog.lowStockThreshold", "invoice.gstin", "invoice.state", "regional.timeZone",
            "storefront.logoUrl", "storefront.legalName", "storefront.address", "storefront.email", "storefront.phone",
            "storefront.grievanceOfficer", "storefront.grievanceEmail");
    private static final FieldPolicy POLICY = Changes.policy(FIELDS, List.of());

    private final TenantRepository tenants;
    private final TenantRegistry registry;
    private final StoreDefaults defaults;
    private final AuditTrail audit;

    StoreSettingsService(TenantRepository tenants, TenantRegistry registry, StoreDefaults defaults, AuditTrail audit) {
        this.tenants = tenants;
        this.registry = registry;
        this.defaults = defaults;
        this.audit = audit;
    }

    public View get() {
        return view(registry.byId(TenantContext.require().id()));
    }

    public View update(Command c) {
        Tenant current = tenants.findById(TenantContext.require().id())
                .orElseThrow(() -> ApiException.notFound("Store not found"));
        StoreSettings own = current.settings();
        StoreSettings settings = new StoreSettings(
                c.commerce() != null ? validCommerce(c.commerce()) : own.commerce(),
                c.catalog() != null ? validCatalog(c.catalog()) : own.catalog(),
                c.invoice() != null ? validInvoice(c.invoice()) : own.invoice(),
                c.regional() != null ? validRegional(c.regional()) : own.regional());
        StorefrontSettings storefront = c.storefront() != null
                ? TenantAdminService.validStorefront(c.storefront()) : current.storefront();
        Tenant updated = tenants.save(current.withOwnerSettings(settings, storefront));
        registry.refresh();
        audit.record(AuditEvent.of(AuditAction.STORE_SETTINGS_UPDATED).on("tenant", updated.id())
                .changing(Changes.diff(auditView(current), auditView(updated), POLICY)));
        return view(updated);
    }

    private View view(Tenant t) {
        return new View(t.settings(), t.storefront(), StoreConfigService.resolve(t.settings(), defaults),
                StoreConfigService.resolve(StoreSettings.NONE, defaults));
    }

    private static StoreSettings.Commerce validCommerce(StoreSettings.Commerce c) {
        range("commerce.shippingFee", c.shippingFee(), 0, 1_000_000);
        range("commerce.freeShippingAbove", c.freeShippingAbove(), 0, 10_000_000);
        range("commerce.defaultTaxRate", c.defaultTaxRate(), 0, 100);
        range("commerce.maxCouponAmount", c.maxCouponAmount(), 1, 10_000_000);
        range("commerce.minimumAge", c.minimumAge(), StoreDefaults.MINIMUM_AGE_FLOOR, 100);
        range("commerce.stuckOrderDays", c.stuckOrderDays(), 1, 60);
        return c;
    }

    private static StoreSettings.Catalog validCatalog(StoreSettings.Catalog c) {
        range("catalog.pageSize", c.pageSize(), 1, 100);
        range("catalog.lowStockThreshold", c.lowStockThreshold(), 0, 1_000_000);
        return c;
    }

    private static StoreSettings.Invoice validInvoice(StoreSettings.Invoice i) {
        String gstin = i.gstin() == null ? null : i.gstin().toUpperCase(Locale.ROOT);
        if (gstin != null && !GSTIN.matcher(gstin).matches()) {
            throw ApiException.badRequest("invoice.gstin: not a valid 15-character GSTIN");
        }
        if (i.state() != null && i.state().length() > 60) {
            throw ApiException.badRequest("invoice.state: at most 60 characters");
        }
        return new StoreSettings.Invoice(gstin, i.state());
    }

    private static StoreSettings.Regional validRegional(StoreSettings.Regional r) {
        if (r.timeZone() != null && !ZoneId.getAvailableZoneIds().contains(r.timeZone())) {
            throw ApiException.badRequest("regional.timeZone: not a known time zone (use a name like Asia/Kolkata)");
        }
        return r;
    }

    private static void range(String field, Number value, long min, long max) {
        if (value != null && (value.doubleValue() < min || value.doubleValue() > max || Double.isNaN(value.doubleValue()))) {
            throw ApiException.badRequest(field + ": must be between " + min + " and " + max);
        }
    }

    private static Map<String, Object> auditView(Tenant t) {
        StoreSettings s = t.settings();
        StorefrontSettings sf = t.storefront();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("commerce.shippingFee", s.commerce().shippingFee());
        v.put("commerce.freeShippingAbove", s.commerce().freeShippingAbove());
        v.put("commerce.defaultTaxRate", s.commerce().defaultTaxRate());
        v.put("commerce.maxCouponAmount", s.commerce().maxCouponAmount());
        v.put("commerce.minimumAge", s.commerce().minimumAge());
        v.put("commerce.stuckOrderDays", s.commerce().stuckOrderDays());
        v.put("catalog.pageSize", s.catalog().pageSize());
        v.put("catalog.lowStockThreshold", s.catalog().lowStockThreshold());
        v.put("invoice.gstin", s.invoice().gstin());
        v.put("invoice.state", s.invoice().state());
        v.put("regional.timeZone", s.regional().timeZone());
        v.put("storefront.logoUrl", sf.logoUrl());
        v.put("storefront.legalName", sf.legalName());
        v.put("storefront.address", sf.address());
        v.put("storefront.email", sf.email());
        v.put("storefront.phone", sf.phone());
        v.put("storefront.grievanceOfficer", sf.grievanceOfficer());
        v.put("storefront.grievanceEmail", sf.grievanceEmail());
        return v;
    }
}
