package com.sixpack.dashboard.domain;

/**
 * One store's totals for one local day: orders placed that day, refunds issued that day (a refund can fall on a
 * later day than its order), and shoppers whose first order fell on that day. Counts and sums only, no customer data.
 */
public record DailySummary(java.time.LocalDate day, long orders, double grossRevenue, double refunds,
                           double discount, double tax, double units, long newCustomers) {

    /** Gross revenue minus refunds, as in contracts.md's metric definitions. */
    public double netRevenue() {
        return grossRevenue - refunds;
    }

    /** The day's order totals in the shape the live queries return. */
    public DayTotals orderTotals() {
        return new DayTotals(orders, grossRevenue, units, discount, tax);
    }
}
