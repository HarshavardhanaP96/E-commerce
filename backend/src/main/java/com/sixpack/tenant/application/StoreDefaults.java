package com.sixpack.tenant.application;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The platform's shop rules for every store that has not set its own ({@code app.store.defaults.*}). Missing or
 * out-of-range values fall back to the rules the platform has always used.
 */
@ConfigurationProperties("app.store.defaults")
public record StoreDefaults(Double shippingFee, Double freeShippingAbove, Double defaultTaxRate,
                            Double maxCouponAmount, Integer minimumAge, Integer pageSize, Integer lowStockThreshold,
                            String timeZone) {

    /** The legal floor: no store may sign up shoppers younger than this. */
    static final int MINIMUM_AGE_FLOOR = 18;

    public StoreDefaults {
        shippingFee = shippingFee == null || shippingFee < 0 ? 200.0 : shippingFee;
        freeShippingAbove = freeShippingAbove == null || freeShippingAbove < 0 ? 1000.0 : freeShippingAbove;
        defaultTaxRate = defaultTaxRate == null || defaultTaxRate < 0 || defaultTaxRate > 100 ? 18.0 : defaultTaxRate;
        maxCouponAmount = maxCouponAmount == null || maxCouponAmount <= 0 ? 100_000.0 : maxCouponAmount;
        minimumAge = minimumAge == null || minimumAge < MINIMUM_AGE_FLOOR ? MINIMUM_AGE_FLOOR : minimumAge;
        pageSize = pageSize == null || pageSize <= 0 || pageSize > 100 ? 10 : pageSize;
        lowStockThreshold = lowStockThreshold == null || lowStockThreshold < 0 ? 5 : lowStockThreshold;
        timeZone = timeZone == null || !ZoneId.getAvailableZoneIds().contains(timeZone) ? "Asia/Kolkata" : timeZone;
    }
}
