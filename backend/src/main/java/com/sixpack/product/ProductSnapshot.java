package com.sixpack.product;

public record ProductSnapshot(String id, String name, String photo, double price, int stock, String sku,
                              Double taxRate) {
}
