package com.sixpack.order;

/** Published after an order is placed, processed or deleted. */
public record OrderChangedEvent(String orderId) {
}
