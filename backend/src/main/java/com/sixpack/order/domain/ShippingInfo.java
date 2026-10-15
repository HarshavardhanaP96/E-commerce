package com.sixpack.order.domain;

public record ShippingInfo(String address, String city, String state, String country, String pinCode) {
}
