package com.sixpack.dashboard.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The shop dashboard's sections for one period (analytics step 3). Revenue here is the gross value of orders
 * placed in the period: order totals, or price × quantity for product figures. Shares are percentages, 1 decimal.
 */
public final class Sections {

    private Sections() {
    }

    public record ProductSales(String productId, String name, double units, double revenue, long orders) {
    }

    public record CategoryShare(String category, double revenue, double units, double share) {
    }

    /** @param daysLeft stock ÷ the period's daily selling rate; 0 when out, null when nothing sold in the period */
    public record StockLeft(String productId, String name, String variantId, String label, int stock, int threshold,
                            double unitsSold, Integer daysLeft) {
    }

    public record IdleProduct(String productId, String name, int stock) {
    }

    /** Two different products in the same {@code orders} orders of the period; productA sorts before productB. */
    public record Pair(String productA, String nameA, String productB, String nameB, long orders) {
    }

    /**
     * Narrowed to an audience segment, every list counts only the segment's orders, and {@code stock} is null (stock
     * cover is the whole store's selling rate).
     *
     * @param stock          null when narrowed to a segment
     * @param trending       against the previous period of the same length; null when {@code small} or
     *                       {@code previousSmall}
     * @param boughtTogether pairs in at least 3 orders, most first, top 10
     * @param small          the period's audience table hides the segment: every list is empty
     * @param previousSmall  the previous period's audience table hides the segment, so there is no trending
     */
    public record Products(List<ProductSales> top, List<CategoryShare> categories, List<StockLeft> stock,
                           List<IdleProduct> idle, Trending trending, List<Pair> boughtTogether, boolean small,
                           boolean previousSmall) {

        /** A segment the period's audience table hides. */
        public static Products hidden() {
            return new Products(List.of(), List.of(), null, List.of(), null, List.of(), true, false);
        }
    }

    /** @param name, email the customer's details on their latest order in the period (null once erased) */
    public record CustomerTotals(String userId, String name, String email, long orders, double revenue) {
    }

    public record Place(String state, String city, long orders, double revenue) {
    }

    /**
     * One gender × age band cell of the audience table, or a row's or column's total. {@code small} true means
     * hidden: every figure is null. An item is hidden when fewer than {@link AudienceBands#MIN_BUYERS} distinct
     * buyers fall in it (an empty one too), and also when a total minus the shown figures would otherwise give such
     * a group away, so a hidden item may hold 5 or more buyers. No group of 1 to 4 buyers can be worked out exactly
     * from one table's figures. The grand total (the "all" row's total) is never hidden.
     *
     * @param buyers distinct buyers with an order in the period
     * @param share  percent of the period's revenue from buyers (all cells of the table add up to 100)
     */
    public record AudienceCell(Long buyers, Long orders, Double revenue, Double share, boolean small) {
    }

    /**
     * @param gender female, male, unknown, or "all" for the total row
     * @param cells  one per age band, in the order of {@link Audience#bands()}
     * @param total  the whole row, across every band
     */
    public record AudienceRow(String gender, List<AudienceCell> cells, AudienceCell total) {
    }

    /**
     * Sales by the buyer's gender and age band, for orders placed in the period by a signed-in buyer.
     *
     * <p>Shape: {@code rows} are female, male, unknown, then "all" (the column totals); each row's {@code cells}
     * follow {@code bands} (18-24, 25-34, 35-44, 45-54, 55+, unknown) and its {@code total} is the row across every
     * band, so the "all" row's total is the whole period. Age is in whole years on {@code asOf}, the period's last
     * day in the store's time zone: a birthday inside the period can move a buyer up one band. No birth date, an
     * age under 18 or a birth date after {@code asOf} is unknown; so is a buyer whose account is gone.
     */
    public record Audience(LocalDate asOf, List<String> bands, List<AudienceRow> rows) {
    }

    /**
     * @param newCustomers       buyers in the period whose first order with the store is in it
     * @param returningCustomers buyers in the period who had ordered before it
     * @param repeatRate         share of the period's buyers with two or more orders in it
     * @param audience           sales by gender and age band
     */
    public record Customers(long customers, long newCustomers, long returningCustomers, double repeatRate,
                            List<CustomerTotals> top, List<Place> places, Audience audience) {
    }

    public record CouponUse(String code, long uses, double discount, double revenue) {
    }

    public record Coupons(long ordersWithCoupon, double shareOfOrders, double discount, List<CouponUse> codes) {
    }

    /** @param share of the period's orders */
    public record MethodShare(String method, long orders, double amount, double share) {
    }

    public record RefundPoint(LocalDate start, long count, double amount) {
    }

    public record Payments(String granularity, List<MethodShare> methods, List<RefundPoint> refunds) {
    }

    /** GST charged on orders placed in a month at one rate, split as on the invoices. */
    public record TaxRow(String month, double rate, double taxableValue, double cgst, double sgst, double igst,
                         double tax) {
    }

    public record Tax(String sellerState, List<TaxRow> rows, double totalTaxable, double totalTax) {
    }

    public static double percent(double part, double whole) {
        return whole == 0 ? 0 : Math.round(part * 1000 / whole) / 10.0;
    }
}
