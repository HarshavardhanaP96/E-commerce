package com.sixpack.shared.tenant;

/**
 * The shop rules a store owner sets for their own store. A null group or field means "use the platform default"
 * ({@code app.store.defaults.*}); {@link StoreConfig} resolves the value in force.
 */
public record StoreSettings(Commerce commerce, Catalog catalog, Invoice invoice, Regional regional) {

    public static final StoreSettings NONE = new StoreSettings(null, null, null, null);

    public StoreSettings {
        commerce = commerce == null ? Commerce.NONE : commerce;
        catalog = catalog == null ? Catalog.NONE : catalog;
        invoice = invoice == null ? Invoice.NONE : invoice;
        regional = regional == null ? Regional.NONE : regional;
    }

    /**
     * Money is in the store's currency (rupees for INR stores).
     *
     * @param freeShippingAbove subtotal above which shipping is free
     * @param defaultTaxRate    GST percent for products that do not set their own
     * @param maxCouponAmount   largest flat discount a coupon may give
     * @param minimumAge        youngest age a shopper may sign up at; never below the platform's legal floor
     * @param stuckOrderDays    an order still Processing after this many days is flagged on Analytics → Orders
     */
    public record Commerce(Double shippingFee, Double freeShippingAbove, Double defaultTaxRate, Double maxCouponAmount,
                           Integer minimumAge, Integer stuckOrderDays) {
        public static final Commerce NONE = new Commerce(null, null, null, null, null, null);
    }

    /**
     * @param pageSize          products per storefront search page
     * @param lowStockThreshold "running low" level for products that do not set their own
     */
    public record Catalog(Integer pageSize, Integer lowStockThreshold) {
        public static final Catalog NONE = new Catalog(null, null);
    }

    /**
     * The store's tax registration as printed on its invoices. The seller's name, address and contact details are the
     * storefront details ({@link StorefrontSettings}).
     *
     * @param state decides CGST+SGST (same state as the buyer) versus IGST, spelled as customers type it
     */
    public record Invoice(String gstin, String state) {
        public static final Invoice NONE = new Invoice(null, null);

        public Invoice {
            gstin = LegalSettings.blankToNull(gstin);
            state = LegalSettings.blankToNull(state);
        }
    }

    /** @param timeZone IANA zone the store's days start and end in (reports, "today") */
    public record Regional(String timeZone) {
        public static final Regional NONE = new Regional(null);

        public Regional {
            timeZone = LegalSettings.blankToNull(timeZone);
        }
    }
}
