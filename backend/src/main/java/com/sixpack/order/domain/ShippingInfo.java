package com.sixpack.order.domain;

/** A delivery or billing address. {@code name} and {@code phone} identify the recipient for the courier. */
public record ShippingInfo(String address, String city, String state, String country, String pinCode, String name,
                           String phone) {

    /** Pre-recipient shape, kept so existing callers compile unchanged. */
    public ShippingInfo(String address, String city, String state, String country, String pinCode) {
        this(address, city, state, country, pinCode, null, null);
    }
}
