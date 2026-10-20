package com.sixpack.shared.tenant;

/** The shop rules in force for the store in {@link TenantContext}: its own setting, else the platform default. */
public interface StoreConfig {

    Effective effective();

    /** Who sold the goods, as the store's invoices print it. */
    Seller seller();

    record Effective(double shippingFee, double freeShippingAbove, double defaultTaxRate, double maxCouponAmount,
                     int minimumAge, int pageSize, int lowStockThreshold, String timeZone) {
    }

    record Seller(String name, String address, String gstin, String state, String email, String phone) {
    }
}
