package com.sixpack.dashboard.domain;

import com.sixpack.dashboard.domain.Sections.CouponUse;
import com.sixpack.dashboard.domain.Sections.CustomerTotals;
import com.sixpack.dashboard.domain.Sections.Place;
import com.sixpack.dashboard.domain.Sections.ProductSales;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Raw figures behind the dashboard sections for the current store, for orders placed in [from, to). */
public interface SectionsReadPort {

    /** Every product sold, by revenue, highest first. */
    List<ProductSales> productSales(Instant from, Instant to);

    /** Units sold per line: the key is the product id, or "productId:variantId" for a variant. */
    Map<String, Double> unitsByLine(Instant from, Instant to);

    /** Revenue and units per product category (looked up on the product; "uncategorised" when missing). */
    Map<String, double[]> categorySales(Instant from, Instant to);

    record OnSale(String productId, String name, int stock) {
    }

    /** Products on sale (status ACTIVE) that existed before {@code createdBefore}. */
    List<OnSale> onSale(Instant createdBefore);

    /** Every buyer in the period, by revenue, highest first. */
    List<CustomerTotals> customerTotals(Instant from, Instant to);

    /** Which of {@code userIds} placed an order before {@code before}. */
    Set<String> orderedBefore(Collection<String> userIds, Instant before);

    /** Orders by delivery state and city, by revenue, highest first. */
    List<Place> places(Instant from, Instant to);

    long orders(Instant from, Instant to);

    /** Coupon codes used, by uses then revenue. */
    List<CouponUse> couponUses(Instant from, Instant to);

    record MethodTotals(String method, long orders, double amount) {
    }

    /** By orders, highest first; a missing method is "unknown". */
    List<MethodTotals> paymentMethods(Instant from, Instant to);

    record RefundIssued(LocalDate day, double amount) {
    }

    /** Refunds issued in [from, to), not failed, by local day in {@code zone}. */
    List<RefundIssued> refunds(Instant from, Instant to, ZoneId zone);

    /**
     * One order's items at one GST rate.
     *
     * @param rate  null on order lines saved before each line carried its rate
     * @param state the billing state, else the delivery state
     */
    record TaxGroup(String orderId, String month, Double rate, String state, double taxableValue) {
    }

    /** Per order and rate, by local month (YYYY-MM) in {@code zone}. */
    List<TaxGroup> taxGroups(Instant from, Instant to, ZoneId zone);
}
