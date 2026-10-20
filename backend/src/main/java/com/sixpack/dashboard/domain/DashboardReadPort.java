package com.sixpack.dashboard.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-side port for analytics. Kept separate from the write-side module repositories so it can be backed by
 * aggregations, a reporting replica or a warehouse without touching the other modules.
 */
public interface DashboardReadPort {

    record OrderTotals(double tax, double shippingCharges, double discount, double total) {
    }

    /**
     * Units and revenue of one product per variant, from the order lines' snapshots.
     *
     * @param variantId null for sales of the product while it had no variants
     * @param label the variant's label on its most recent order (it survives renames and deletions)
     */
    record VariantSales(String variantId, String label, String sku, long units, double revenue, long orders) {
    }

    record LatestTransaction(String id, double discount, double amount, int quantity, String status) {
    }

    /** Counts documents created in [from, to); null bounds are open. */
    long count(Dataset dataset, Scope scope, Instant from, Instant to);

    /** Sum of order totals created in [from, to); null bounds are open. */
    double revenue(Scope scope, Instant from, Instant to);

    /** Per-month count (sumField null) or sum of an order field ("total", "discount") since {@code from}. */
    Map<YearMonth, Double> monthly(Dataset dataset, Scope scope, Instant from, String sumField);

    Map<String, Long> productsByCategory(Scope scope);

    Map<String, Long> usersByGender(Scope scope);

    Map<String, Long> usersByRole(Scope scope);

    Map<String, Long> ordersByStatus(Scope scope);

    long outOfStock(Scope scope);

    OrderTotals orderTotals(Scope scope);

    List<LocalDate> userBirthDates(Scope scope);

    List<LatestTransaction> latestOrders(Scope scope, int limit);

    /** Sales of one product grouped by variant, best sellers first. */
    List<VariantSales> variantSales(String productId);

    /**
     * Orders placed in [from, to), by local day in {@code zone}. Narrowed to a product or category, only the
     * matching items count (see {@link DayTotals}).
     */
    Map<LocalDate, DayTotals> dailyOrders(Scope scope, Instant from, Instant to, ZoneId zone);

    /** Refunds issued in [from, to) on orders in scope, by local day in {@code zone}; failed refunds are left out. */
    Map<LocalDate, Double> dailyRefunds(Scope scope, Instant from, Instant to, ZoneId zone);

    /** When the store's first order was placed, if it has any. */
    Optional<Instant> firstOrderAt();

    /** Shoppers whose first order was placed in [from, to), by local day in {@code zone}. */
    Map<LocalDate, Long> newCustomers(Instant from, Instant to, ZoneId zone);
}
