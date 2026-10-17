package com.sixpack.order.domain;

import java.time.Instant;

/** One step in an order's life. {@code actor} is the id of the user who caused it. */
public record StatusEntry(OrderStatus status, Instant at, String actor) {
}
