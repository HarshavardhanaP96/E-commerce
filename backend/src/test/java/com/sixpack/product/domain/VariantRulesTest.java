package com.sixpack.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.product.domain.VariantRules.AxisInput;
import com.sixpack.product.domain.VariantRules.ValueInput;
import com.sixpack.product.domain.VariantRules.VariantInput;
import com.sixpack.shared.ApiException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class VariantRulesTest {

    private final AtomicInteger counter = new AtomicInteger();
    private final Supplier<String> ids = () -> "v" + counter.incrementAndGet();

    private static AxisInput size(String... labels) {
        return new AxisInput("Size", "text", java.util.Arrays.stream(labels)
                .map(l -> new ValueInput(l, null, null)).toList());
    }

    private static AxisInput colour(String... labels) {
        return new AxisInput("Colour", "colour", java.util.Arrays.stream(labels)
                .map(l -> new ValueInput(l, "#111111", null)).toList());
    }

    private static VariantInput variant(String sku, double price, Integer stock, String... choices) {
        var options = new java.util.ArrayList<OptionChoice>();
        for (int i = 0; i < choices.length; i += 2) {
            options.add(new OptionChoice(choices[i], choices[i + 1]));
        }
        return new VariantInput(null, sku, options, price, null, stock, null);
    }

    private ProductVariants build(ProductVariants current, List<AxisInput> axes, List<VariantInput> items) {
        return VariantRules.build(current, axes, items, "SHIRT", null, ids);
    }

    private ProductVariants build(List<AxisInput> axes, List<VariantInput> items) {
        return build(ProductVariants.EMPTY, axes, items);
    }

    private void assertRejected(List<AxisInput> axes, List<VariantInput> items, String messagePart) {
        assertThatThrownBy(() -> build(axes, items)).isInstanceOf(ApiException.class)
                .hasMessageContaining(messagePart);
    }

    @Test
    void buildsAMatrixWithGeneratedIdsAndSkus() {
        ProductVariants built = build(List.of(size("S", "M"), colour("Black")), List.of(
                variant(null, 499, 5, "Size", "S", "Colour", "Black"),
                variant("SH-M-BLK", 549, 3, "size", "m", "colour", "black")));

        assertThat(built.axes()).extracting(OptionAxis::name).containsExactly("Size", "Colour");
        assertThat(built.axes().get(1).type()).isEqualTo(OptionType.COLOUR);
        assertThat(built.items()).extracting(Variant::id).containsExactly("v1", "v2");
        assertThat(built.items().get(0).sku()).isEqualTo("SHIRT-S-BLACK");
        assertThat(built.items().get(1).sku()).isEqualTo("SH-M-BLK");
        // option names and values are normalised to the axis spelling
        assertThat(built.items().get(1).options()).containsExactly(new OptionChoice("Size", "M"),
                new OptionChoice("Colour", "Black"));
        assertThat(built.items().get(1).label()).isEqualTo("M / Black");
        assertThat(built.totalStock()).isEqualTo(8);
        assertThat(built.lowestPrice()).isEqualTo(499);
    }

    @Test
    void emptyInputMeansNoVariants() {
        assertThat(build(List.of(), List.of())).isEqualTo(ProductVariants.EMPTY);
        assertThat(build(null, null)).isEqualTo(ProductVariants.EMPTY);
    }

    @Test
    void axesWithoutVariantsAndVariantsWithoutAxesAreRejected() {
        assertRejected(List.of(size("S")), List.of(), "variants");
        assertRejected(List.of(), List.of(variant(null, 10, 1, "Size", "S")), "axes");
    }

    @Test
    void axisRulesAreEnforced() {
        var v = List.of(variant(null, 10, 1, "A", "1"));
        assertRejected(List.of(size("S"), size("M")), v, "duplicate");
        assertRejected(List.of(new AxisInput("A", null, List.of(new ValueInput("1", null, null))),
                new AxisInput("B", null, List.of(new ValueInput("1", null, null))),
                new AxisInput("C", null, List.of(new ValueInput("1", null, null))),
                new AxisInput("D", null, List.of(new ValueInput("1", null, null)))), v, "at most 3");
        assertRejected(List.of(new AxisInput("A", null, List.of())), v, "at least one value");
        assertRejected(List.of(new AxisInput("A", null, List.of(new ValueInput("1", null, null),
                new ValueInput("1", null, null)))), v, "duplicate");
        assertRejected(List.of(new AxisInput(" ", null, List.of(new ValueInput("1", null, null)))), v, "name");
    }

    @Test
    void axisNamesCannotContainTheSearchSeparator() {
        assertRejected(List.of(new AxisInput("Size: EU", null, List.of(new ValueInput("1", null, null)))),
                List.of(variant(null, 10, 1, "Size: EU", "1")), "':'");
    }

    @Test
    void twentyOneValuesOnAnAxisAreRejected() {
        var values = new java.util.ArrayList<ValueInput>();
        for (int i = 0; i < 21; i++) {
            values.add(new ValueInput("v" + i, null, null));
        }
        assertRejected(List.of(new AxisInput("A", null, values)), List.of(variant(null, 10, 1, "A", "v0")),
                "at most 20");
    }

    @Test
    void hexMustBeAColourSwatchOnAColourAxis() {
        var v = List.of(variant(null, 10, 1, "Colour", "Black"));
        assertRejected(List.of(new AxisInput("Colour", "colour",
                List.of(new ValueInput("Black", "black", null)))), v, "hex");
        assertRejected(List.of(new AxisInput("Colour", "text",
                List.of(new ValueInput("Black", "#000000", null)))), v, "hex");
        ProductVariants ok = build(List.of(new AxisInput("Colour", "colour",
                List.of(new ValueInput("Black", "#AABBCC", null)))), v);
        assertThat(ok.axes().get(0).values().get(0).hex()).isEqualTo("#aabbcc");
    }

    @Test
    void everyVariantMustPickExactlyOneValuePerAxis() {
        var axes = List.of(size("S", "M"), colour("Black"));
        assertRejected(axes, List.of(variant(null, 10, 1, "Size", "S")), "every option");
        assertRejected(axes, List.of(variant(null, 10, 1, "Size", "XL", "Colour", "Black")), "XL");
        assertRejected(axes, List.of(variant(null, 10, 1, "Size", "S", "Colour", "Black", "Fit", "Slim")),
                "Fit");
        assertRejected(axes, List.of(variant(null, 10, 1, "Size", "S", "Size", "M", "Colour", "Black")),
                "every option");
    }

    @Test
    void duplicateCombinationsAndSkusAreRejected() {
        var axes = List.of(size("S", "M"));
        assertRejected(axes, List.of(variant("A", 10, 1, "Size", "S"), variant("B", 10, 1, "Size", "s")),
                "same options");
        assertRejected(axes, List.of(variant("A", 10, 1, "Size", "S"), variant("A", 10, 1, "Size", "M")),
                "sku");
    }

    @Test
    void variantSkuMustDifferFromTheProductSku() {
        assertThatThrownBy(() -> VariantRules.build(ProductVariants.EMPTY, List.of(size("S")),
                List.of(variant("P-1", 10, 1, "Size", "S")), "P-1", "P-1", ids))
                .isInstanceOf(ApiException.class).hasMessageContaining("sku");
    }

    @Test
    void priceAndMrpAndStockRulesAreEnforced() {
        var axes = List.of(size("S"));
        assertRejected(axes, List.of(variant(null, 0, 1, "Size", "S")), "price");
        assertRejected(axes, List.of(variant(null, 10, -1, "Size", "S")), "stock");
        assertRejected(axes, List.of(new VariantInput(null, null, List.of(new OptionChoice("Size", "S")), 100.0,
                50.0, 1, null)), "mrp");
        assertRejected(axes, List.of(new VariantInput(null, null, List.of(new OptionChoice("Size", "S")), 100.0,
                null, 1, "paused")), "status");
    }

    @Test
    void moreThanAHundredVariantsAreRejected() {
        var values = new java.util.ArrayList<ValueInput>();
        for (int i = 0; i < 20; i++) {
            values.add(new ValueInput("v" + i, null, null));
        }
        var axes = List.of(new AxisInput("A", null, values), new AxisInput("B", null, values),
                new AxisInput("C", null, values));
        var items = new java.util.ArrayList<VariantInput>();
        for (int a = 0; a < 5; a++) {
            for (int b = 0; b < 5; b++) {
                for (int c = 0; c < 5; c++) {
                    items.add(variant(null, 10, 1, "A", "v" + a, "B", "v" + b, "C", "v" + c));
                }
            }
        }
        assertRejected(axes, items, "at most 100");
    }

    @Test
    void existingVariantsKeepTheirStockAndIdAndAreEditedInPlace() {
        ProductVariants current = build(List.of(size("S", "M")), List.of(
                variant("SKU-S", 100, 7, "Size", "S"), variant("SKU-M", 100, 4, "Size", "M")));
        String sId = current.items().get(0).id();

        var edit = new VariantInput(sId, null, List.of(new OptionChoice("Size", "S")), 120.0, null, 999, "disabled");
        ProductVariants next = build(current, List.of(size("S", "M", "L")), List.of(edit,
                variant("SKU-L", 130, 2, "Size", "L")));

        assertThat(next.items()).hasSize(2);   // M was omitted, so it is gone
        Variant s = next.items().get(0);
        assertThat(s.id()).isEqualTo(sId);
        assertThat(s.sku()).isEqualTo("SKU-S");       // blank sku keeps the current one
        assertThat(s.stock()).isEqualTo(7);           // input stock is ignored for existing variants
        assertThat(s.price()).isEqualTo(120);
        assertThat(s.status()).isEqualTo(VariantStatus.DISABLED);
        assertThat(next.items().get(1).stock()).isEqualTo(2);   // new variants take their initial stock
        assertThat(next.lowestPrice()).isEqualTo(130);          // the disabled variant does not set the price
    }

    @Test
    void unknownOrRepeatedVariantIdsAreRejected() {
        ProductVariants current = build(List.of(size("S")), List.of(variant("A", 10, 1, "Size", "S")));
        var ghost = new VariantInput("nope", null, List.of(new OptionChoice("Size", "S")), 10.0, null, null, null);
        assertThatThrownBy(() -> build(current, List.of(size("S")), List.of(ghost)))
                .isInstanceOf(ApiException.class).hasMessageContaining("unknown");
        String id = current.items().get(0).id();
        var once = new VariantInput(id, null, List.of(new OptionChoice("Size", "S")), 10.0, null, null, null);
        var twice = new VariantInput(id, null, List.of(new OptionChoice("Size", "M")), 10.0, null, null, null);
        assertThatThrownBy(() -> build(current, List.of(size("S", "M")), List.of(once, twice)))
                .isInstanceOf(ApiException.class).hasMessageContaining("more than once");
    }

    @Test
    void lowestPriceFallsBackToAllVariantsWhenNoneIsSellable() {
        ProductVariants built = new ProductVariants(List.of(), List.of(
                new Variant("a", "A", List.of(), 50, null, 0, VariantStatus.DISABLED),
                new Variant("b", "B", List.of(), 30, null, 0, VariantStatus.DISABLED)));
        assertThat(built.lowestPrice()).isEqualTo(30);
    }
}
