package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.Sections.ProductSales;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrendingTest {

    private static ProductSales sold(String id, double units) {
        return new ProductSales(id, id.toUpperCase(), units, units * 100, 1);
    }

    @Test
    void productsSellingFewerThanThreeUnitsInBothPeriodsAreLeftOut() {
        Trending t = Trending.of(List.of(sold("tee", 2), sold("cap", 3)), List.of(sold("tee", 1), sold("hat", 2)), 10);
        assertThat(t.rising()).extracting(Trending.Row::productId).containsExactly("cap");
        assertThat(t.falling()).isEmpty();
    }

    @Test
    void aProductWithNoSalesBeforeIsNewWithNoChangeFigure() {
        Trending t = Trending.of(List.of(sold("cap", 4)), List.of(), 10);
        assertThat(t.rising()).containsExactly(new Trending.Row("cap", "CAP", 4, 0, null, true));
    }

    @Test
    void risingAndFallingCarryThePercentChange() {
        Trending t = Trending.of(List.of(sold("shoe", 8), sold("mug", 5)), List.of(sold("shoe", 2), sold("mug", 6)), 10);
        assertThat(t.rising()).containsExactly(new Trending.Row("shoe", "SHOE", 8, 2, 300L, false));
        assertThat(t.falling()).containsExactly(new Trending.Row("mug", "MUG", 5, 6, -17L, false));
    }

    @Test
    void aProductThatStoppedSellingFallsByAHundredPercent() {
        Trending t = Trending.of(List.of(), List.of(sold("bag", 6)), 10);
        assertThat(t.falling()).containsExactly(new Trending.Row("bag", "BAG", 0, 6, -100L, false));
        assertThat(t.rising()).isEmpty();
    }

    @Test
    void rankedByTheChangeInUnitsNotThePercent() {
        Trending t = Trending.of(
                List.of(sold("pin", 4), sold("bag", 15), sold("shoe", 7), sold("cap", 3), sold("lamp", 10), sold("rug", 1)),
                List.of(sold("pin", 1), sold("bag", 10), sold("shoe", 1), sold("lamp", 20), sold("rug", 5)), 10);
        // +6 shoe, +5 bag, +3 pin (300%), +3 cap (new); ties by name
        assertThat(t.rising()).extracting(Trending.Row::productId).containsExactly("shoe", "bag", "cap", "pin");
        // -10 lamp (-50%), -4 rug (-80%)
        assertThat(t.falling()).extracting(Trending.Row::productId).containsExactly("lamp", "rug");
    }

    @Test
    void unchangedProductsAreInNeitherList() {
        Trending t = Trending.of(List.of(sold("mug", 5)), List.of(sold("mug", 5)), 10);
        assertThat(t.rising()).isEmpty();
        assertThat(t.falling()).isEmpty();
    }

    @Test
    void limitCutsEachListAndZeroKeepsEveryRow() {
        List<ProductSales> now = List.of(sold("a", 9), sold("b", 8), sold("c", 7), sold("x", 0));
        List<ProductSales> before = List.of(sold("x", 9), sold("y", 8));
        Trending one = Trending.of(now, before, 1);
        assertThat(one.rising()).extracting(Trending.Row::productId).containsExactly("a");
        assertThat(one.falling()).extracting(Trending.Row::productId).containsExactly("x");
        Trending all = Trending.of(now, before, 0);
        assertThat(all.rising()).hasSize(3);
        assertThat(all.falling()).extracting(Trending.Row::productId).containsExactly("x", "y");
    }

    @Test
    void theNameComesFromThisPeriodElseTheOneBefore() {
        Trending t = Trending.of(List.of(new ProductSales("mug", null, 1, 100, 1), new ProductSales("cup", "Cup", 6, 600, 2)),
                List.of(new ProductSales("mug", "Old mug", 5, 500, 1), new ProductSales("cup", "Old cup", 3, 300, 1)), 10);
        assertThat(t.falling()).extracting(Trending.Row::name).containsExactly("Old mug");
        assertThat(t.rising()).extracting(Trending.Row::name).containsExactly("Cup");
    }

    @Test
    void tiesWithoutANameStillSortWithoutFailing() {
        Trending t = Trending.of(List.of(new ProductSales("b", null, 3, 0, 1), new ProductSales("a", null, 3, 0, 1),
                sold("c", 3)), List.of(), 10);
        assertThat(t.rising()).extracting(Trending.Row::productId).containsExactly("c", "a", "b");
    }

    @Test
    void linesWithoutAProductAreLeftOut() {
        Trending t = Trending.of(List.of(new ProductSales(null, "Gift wrap", 9, 90, 9)), List.of(), 10);
        assertThat(t.rising()).isEmpty();
    }

    @Test
    void emptyPeriods() {
        Trending t = Trending.of(List.of(), List.of(), 10);
        assertThat(t.rising()).isEmpty();
        assertThat(t.falling()).isEmpty();
    }
}
