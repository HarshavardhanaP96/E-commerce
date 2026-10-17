package com.sixpack.coupon.domain;

/** Outcome of checking a coupon against a cart: either a discount, or the reason it was refused. */
public record Evaluation(double discount, String rejection) {

    public static Evaluation accept(double discount) {
        return new Evaluation(discount, null);
    }

    public static Evaluation reject(String reason) {
        return new Evaluation(0, reason);
    }

    public boolean accepted() {
        return rejection == null;
    }
}
