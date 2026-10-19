package com.sixpack.product.domain;

import java.util.List;

/** An option name with every value used by public products, for the storefront filter. */
public record OptionFacet(String name, OptionType type, List<Value> values) {

    public record Value(String label, String hex) {
    }
}
