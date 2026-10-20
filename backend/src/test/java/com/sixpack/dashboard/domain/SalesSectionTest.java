package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.SalesReads.MethodTotals;
import com.sixpack.dashboard.domain.SalesReads.ProductRevenue;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SalesSectionTest {

    private static final Map<String, String> CATEGORIES = Map.of("shoe", "footwear", "bag", "bags");

    @Test
    void revenueByCategoryWithShareAndChange() {
        List<SalesSection.CategoryRow> rows = SalesSection.byCategory(
                List.of(new ProductRevenue("shoe", 1000, 2), new ProductRevenue("bag", 300, 1),
                        new ProductRevenue("gone", 200, 1)),
                List.of(new ProductRevenue("shoe", 500, 2)), CATEGORIES);
        assertThat(rows).extracting(SalesSection.CategoryRow::category)
                .containsExactly("footwear", "bags", "uncategorised");
        assertThat(rows.get(0).share()).isEqualTo(66.7);
        assertThat(rows.get(0).change()).isEqualTo(100L);
        assertThat(rows.get(1).change()).isEqualTo(100L); // nothing before counts as +100%, like /summary
        assertThat(rows.get(2).units()).isEqualTo(1);
    }

    @Test
    void noComparisonMeansNoChange() {
        List<SalesSection.CategoryRow> rows = SalesSection.byCategory(
                List.of(new ProductRevenue("shoe", 10, 1)), null, CATEGORIES);
        assertThat(rows.get(0).change()).isNull();
    }

    @Test
    void breakdownNetIsChargedMinusRefunds() {
        SalesSection.Breakdown b = SalesSection.breakdown(new SalesReads.Breakdown(3, 1550, 100, 50, 100, 1), 200);
        assertThat(b.charged()).isEqualTo(1550);
        assertThat(b.net()).isEqualTo(1350);
        assertThat(b.couponOrderShare()).isEqualTo(33.3);
    }

    @Test
    void emptyPeriodHasNoRowsAndNoDivisionByZero() {
        assertThat(SalesSection.byCategory(List.of(), List.of(), CATEGORIES)).isEmpty();
        assertThat(SalesSection.breakdown(new SalesReads.Breakdown(0, 0, 0, 0, 0, 0), 0).couponOrderShare()).isZero();
        assertThat(SalesSection.methods(List.of())).isEmpty();
    }

    @Test
    void paymentMethodsByOrdersThenName() {
        assertThat(SalesSection.methods(List.of(new MethodTotals("upi", 1, 1050), new MethodTotals("card", 1, 300),
                new MethodTotals("unknown", 1, 200))))
                .extracting(SalesSection.MethodRow::method).containsExactly("card", "unknown", "upi");
    }

    @Test
    void csvRowsPutTheComparisonBesideEachBucket() {
        Window w = new Window(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), null, null, "UTC", "day");
        SalesSection s = new SalesSection(w, "net",
                List.of(new Series.Point(LocalDate.of(2026, 10, 1), 10, 1), new Series.Point(LocalDate.of(2026, 10, 2), 20, 2)),
                List.of(new Series.Point(LocalDate.of(2026, 9, 29), 5, 1)), List.of(), null, null);
        assertThat(s.csvHeader()).containsExactly("start", "revenue", "orders", "previousRevenue");
        assertThat(s.csvRows()).containsExactly(Arrays.asList(LocalDate.of(2026, 10, 1), 10.0, 1L, 5.0),
                Arrays.asList(LocalDate.of(2026, 10, 2), 20.0, 2L, null));
    }
}
