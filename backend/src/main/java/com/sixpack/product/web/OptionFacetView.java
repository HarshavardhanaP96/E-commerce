package com.sixpack.product.web;

import com.sixpack.product.domain.OptionFacet;
import java.util.List;

record OptionFacetView(String name, String type, List<ValueView> values) {

    record ValueView(String label, String hex) {
    }

    static OptionFacetView from(OptionFacet f) {
        return new OptionFacetView(f.name(), f.type().name(),
                f.values().stream().map(v -> new ValueView(v.label(), v.hex())).toList());
    }
}
