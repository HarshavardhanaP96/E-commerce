package com.sixpack.product.domain;

import java.util.List;

/** One choice on an axis, e.g. "M" or "Black". Images belong to the value so a colour is uploaded once. */
public record OptionValue(String label, String hex, List<String> images) {

    public OptionValue {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
