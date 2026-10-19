package com.sixpack.product.domain;

import java.util.List;

/** A dimension a product varies along, e.g. Size or Colour. Value order is display order. */
public record OptionAxis(String name, OptionType type, List<OptionValue> values) {

    public OptionAxis {
        type = type == null ? OptionType.TEXT : type;
        values = values == null ? List.of() : List.copyOf(values);
    }
}
