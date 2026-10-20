package com.sixpack.dashboard.domain;

/**
 * Orders placed on one day. {@code sales} is the order totals, or, when the view is narrowed to a product or
 * category, the price times quantity of the matching items only; {@code discount} and {@code tax} are then null
 * because they belong to the whole order.
 */
public record DayTotals(long orders, double sales, double units, Double discount, Double tax) {
}
