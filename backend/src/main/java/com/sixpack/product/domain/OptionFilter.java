package com.sixpack.product.domain;

/** Search filter: the product must have a sellable, in-stock variant with this option value. */
public record OptionFilter(String name, String value) {
}
