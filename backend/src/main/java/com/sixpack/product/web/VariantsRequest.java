package com.sixpack.product.web;

import com.sixpack.product.application.ProductService.VariantsCommand;
import com.sixpack.product.domain.OptionChoice;
import com.sixpack.product.domain.VariantRules.AxisInput;
import com.sixpack.product.domain.VariantRules.ValueInput;
import com.sixpack.product.domain.VariantRules.VariantInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Body of {@code PUT /product/{id}/variants}. The annotations only bound the payload size; the business rules
 * live in {@link com.sixpack.product.domain.VariantRules}.
 */
record VariantsRequest(
        @Size(max = 3) List<@Valid Axis> axes,
        @Size(max = 100) List<@Valid Variant> variants) {

    record Axis(@Size(max = 100) String name, @Size(max = 10) String type,
                @Size(max = 20) List<@Valid Value> values) {
    }

    record Value(@Size(max = 100) String label, @Size(max = 10) String hex,
                 @Size(max = 5) List<@Size(max = 200) String> images) {
    }

    record Variant(@Size(max = 40) String id, @Size(max = 100) String sku,
                   @Size(max = 3) List<@Valid Choice> options, Double price, Double mrp, Integer stock,
                   @Size(max = 20) String status) {
    }

    record Choice(@Size(max = 100) String name, @Size(max = 100) String value) {
    }

    VariantsCommand toCommand() {
        List<AxisInput> axisInputs = axes == null ? null : axes.stream()
                .map(a -> new AxisInput(a.name(), a.type(), a.values() == null ? null : a.values().stream()
                        .map(v -> new ValueInput(v.label(), v.hex(), v.images())).toList()))
                .toList();
        List<VariantInput> variantInputs = variants == null ? null : variants.stream()
                .map(v -> new VariantInput(v.id(), v.sku(), v.options() == null ? null : v.options().stream()
                        .map(c -> new OptionChoice(c.name(), c.value())).toList(), v.price(), v.mrp(), v.stock(),
                        v.status()))
                .toList();
        return new VariantsCommand(axisInputs, variantInputs);
    }
}
