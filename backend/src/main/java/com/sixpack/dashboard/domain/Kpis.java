package com.sixpack.dashboard.domain;

import java.util.Collection;

/**
 * Headline numbers for a period. {@code refunds}, {@code discount} and {@code tax} are null when the view is
 * narrowed to a product or category, since they cannot be split by item.
 */
public record Kpis(double revenue, long orders, double averageOrderValue, double units, Double refunds,
                   Double discount, Double tax) {

    public record Change(long revenue, long orders, long averageOrderValue, long units) {
    }

    /** @param refunds refunds issued in the period, or null when the view is narrowed to items */
    public static Kpis of(Collection<DayTotals> days, Double refunds, Basis basis) {
        boolean itemScoped = refunds == null;
        long orders = 0;
        double sales = 0;
        double units = 0;
        double discount = 0;
        double tax = 0;
        for (DayTotals d : days) {
            orders += d.orders();
            sales += d.sales();
            units += d.units();
            discount += d.discount() == null ? 0 : d.discount();
            tax += d.tax() == null ? 0 : d.tax();
        }
        double revenue = basis == Basis.NET && !itemScoped ? sales - refunds : sales;
        return new Kpis(revenue, orders, orders == 0 ? 0 : revenue / orders, units, refunds,
                itemScoped ? null : discount, itemScoped ? null : tax);
    }

    public Change changeFrom(Kpis before) {
        return new Change(MonthBuckets.percentChange(revenue, before.revenue),
                MonthBuckets.percentChange(orders, before.orders),
                MonthBuckets.percentChange(averageOrderValue, before.averageOrderValue),
                MonthBuckets.percentChange(units, before.units));
    }
}
