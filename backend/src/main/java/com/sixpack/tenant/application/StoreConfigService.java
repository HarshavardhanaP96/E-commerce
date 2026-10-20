package com.sixpack.tenant.application;

import com.sixpack.shared.tenant.StoreConfig;
import com.sixpack.shared.tenant.StoreSettings;
import com.sixpack.shared.tenant.StorefrontSettings;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.tenant.domain.Tenant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Store setting first, then the platform default. Reads the tenant through the registry's cache. */
@Component
class StoreConfigService implements StoreConfig {

    private final TenantRegistry registry;
    private final StoreDefaults defaults;
    private final InvoiceProperties platformSeller;
    private final TenantProperties tenants;

    StoreConfigService(TenantRegistry registry, StoreDefaults defaults, InvoiceProperties platformSeller,
                       TenantProperties tenants) {
        this.registry = registry;
        this.defaults = defaults;
        this.platformSeller = platformSeller;
        this.tenants = tenants;
    }

    private Tenant tenant() {
        return registry.byId(TenantContext.require().id());
    }

    @Override
    public Effective effective() {
        return resolve(tenant().settings(), defaults);
    }

    static Effective resolve(StoreSettings own, StoreDefaults d) {
        StoreSettings.Commerce c = own.commerce();
        StoreSettings.Catalog cat = own.catalog();
        return new Effective(or(c.shippingFee(), d.shippingFee()), or(c.freeShippingAbove(), d.freeShippingAbove()),
                or(c.defaultTaxRate(), d.defaultTaxRate()), or(c.maxCouponAmount(), d.maxCouponAmount()),
                or(c.minimumAge(), d.minimumAge()), or(cat.pageSize(), d.pageSize()),
                or(cat.lowStockThreshold(), d.lowStockThreshold()), or(own.regional().timeZone(), d.timeZone()));
    }

    @Override
    public String currency() {
        String currency = tenant().currency();
        return currency == null || currency.isBlank() ? "INR" : currency;
    }

    @Override
    public Seller seller() {
        Tenant t = tenant();
        StorefrontSettings sf = t.storefront();
        StoreSettings.Invoice reg = t.settings().invoice();
        boolean platformFallback = t.slug().equals(tenants.defaultSlug());
        Seller p = platformSeller.seller();
        return new Seller(
                pick(sf.legalName(), platformFallback ? p::name : t::name),
                pick(sf.address(), platformFallback ? p::address : () -> null),
                pick(reg.gstin(), platformFallback ? p::gstin : () -> null),
                pick(reg.state(), platformFallback ? p::state : () -> null),
                pick(sf.email(), platformFallback ? p::email : () -> null),
                pick(sf.phone(), platformFallback ? p::phone : () -> null));
    }

    private static <T> T or(T own, T fallback) {
        return own != null ? own : fallback;
    }

    private static String pick(String own, Supplier<String> fallback) {
        if (own != null) {
            return own;
        }
        String f = fallback.get();
        return f == null || f.isBlank() ? null : f;
    }
}
