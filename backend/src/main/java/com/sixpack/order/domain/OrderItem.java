package com.sixpack.order.domain;

/** Snapshot of the product at purchase time, so later product edits/deletes do not change history. */
public record OrderItem(String productId, String name, String photo, double price, int quantity) {
}
