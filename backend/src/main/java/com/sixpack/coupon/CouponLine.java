package com.sixpack.coupon;

/**
 * One priced cart line as the coupon module needs to see it.
 *
 * @param category the product's normalised category, or null if unknown
 * @param amount   the line total in rupees (unit price × quantity)
 */
public record CouponLine(String productId, String category, double amount) {
}
