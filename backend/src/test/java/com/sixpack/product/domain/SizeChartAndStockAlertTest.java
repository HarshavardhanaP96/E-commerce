package com.sixpack.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SizeChartAndStockAlertTest {

    @Test
    void sizeChartIsTrimmedAndValidated() {
        SizeChart chart = SizeChart.of(List.of(" Size ", "Chest (in)"),
                List.of(List.of("S", " 38 "), List.of("M", "")), "  Measured flat  ");

        assertThat(chart.columns()).containsExactly("Size", "Chest (in)");
        assertThat(chart.rows()).containsExactly(List.of("S", "38"), List.of("M", ""));
        assertThat(chart.note()).isEqualTo("Measured flat");
        assertThat(SizeChart.of(List.of(), List.of(), "x")).isEqualTo(SizeChart.EMPTY);
        assertThat(SizeChart.of(null, null, null).isEmpty()).isTrue();
    }

    @Test
    void badSizeChartsAreRejected() {
        assertBad(List.of("Size"), List.of(), "rows");
        assertBad(List.of(), List.of(List.of("S")), "columns");
        assertBad(List.of("Size", " "), List.of(List.of("S", "1")), "heading");
        assertBad(List.of("Size", "Chest"), List.of(List.of("S")), "exactly 2");
        assertBad(List.of("Size"), List.of(List.of(" ")), "empty rows");
        assertBad(List.of("Size"), List.of(List.of("x".repeat(31))), "30 characters");
        assertBad(Collections.nCopies(9, "c"), List.of(Collections.nCopies(9, "1")), "columns");
        var tooMany = new ArrayList<List<String>>();
        for (int i = 0; i < 31; i++) {
            tooMany.add(List.of("r" + i));
        }
        assertBad(List.of("Size"), tooMany, "rows");
    }

    private static void assertBad(List<String> columns, List<List<String>> rows, String part) {
        assertThatThrownBy(() -> SizeChart.of(columns, rows, null)).isInstanceOf(ApiException.class)
                .hasMessageContaining(part);
    }

    private static Product simple(int stock, Integer threshold, ProductStatus status) {
        return new Product("p1", "Mug", "m.png", 100, stock, "home", null,
                new ProductDetails(null, "MUG-1", null, null, null, null, null, status, null, null, null, null, null,
                        threshold, null));
    }

    @Test
    void simpleProductAlertsUseItsOwnThresholdOrTheStoreDefault() {
        assertThat(simple(5, null, ProductStatus.ACTIVE).stockAlerts(5)).hasSize(1);
        assertThat(simple(6, null, ProductStatus.ACTIVE).stockAlerts(5)).isEmpty();
        assertThat(simple(6, 10, ProductStatus.ACTIVE).stockAlerts(5)).singleElement()
                .satisfies(a -> {
                    assertThat(a.threshold()).isEqualTo(10);
                    assertThat(a.sku()).isEqualTo("MUG-1");
                    assertThat(a.variantId()).isNull();
                    assertThat(a.isOut()).isFalse();
                });
        assertThat(simple(0, 0, ProductStatus.ACTIVE).stockAlerts(5)).singleElement()
                .satisfies(a -> assertThat(a.isOut()).isTrue());
        assertThat(simple(1, 0, ProductStatus.ACTIVE).stockAlerts(5)).isEmpty();
        assertThat(simple(0, null, ProductStatus.DRAFT).stockAlerts(5)).isEmpty();
    }

    @Test
    void variantAlertsCoverOnlyActiveVariantsAtOrBelowTheThreshold() {
        ProductVariants variants = new ProductVariants(List.of(), List.of(
                new Variant("a", "T-S", List.of(new OptionChoice("Size", "S")), 100, null, 2, VariantStatus.ACTIVE),
                new Variant("b", "T-M", List.of(new OptionChoice("Size", "M")), 100, null, 9, VariantStatus.ACTIVE),
                new Variant("c", "T-L", List.of(new OptionChoice("Size", "L")), 100, null, 0, VariantStatus.DISABLED)));
        Product tee = simple(0, 3, ProductStatus.ACTIVE).withVariants(variants);

        assertThat(tee.stockAlerts(5)).singleElement().satisfies(a -> {
            assertThat(a.variantId()).isEqualTo("a");
            assertThat(a.label()).isEqualTo("S");
            assertThat(a.sku()).isEqualTo("T-S");
            assertThat(a.stock()).isEqualTo(2);
        });
    }
}
