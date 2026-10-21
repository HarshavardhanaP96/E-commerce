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

    public record Products(List<ProductSales> top, List<CategoryShare> categories, List<StockLeft> stock,
                           List<IdleProduct> idle) {
    }

    /** @param name, email the customer's details on their latest order in the period (null once erased) */
    public record CustomerTotals(String userId, String name, String email, long orders, double revenue) {
    }

    public record Place(String state, String city, long orders, double revenue) {
    }

    /**
     * @param newCustomers       buyers in the period whose first order with the store is in it
     * @param returningCustomers buyers in the period who had ordered before it
     * @param repeatRate         share of the period's buyers with two or more orders in it
     */
    public record Customers(long customers, long newCustomers, long returningCustomers, double repeatRate,
                            List<CustomerTotals> top, List<Place> places) {
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
