package com.sixpack.order.domain;

/** Contact details of the buyer at the time of purchase, kept so later profile edits don't rewrite history. */
public record Customer(String name, String email, String phone) {
}
