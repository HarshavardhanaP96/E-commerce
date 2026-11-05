package com.sixpack.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.ApiException;
import org.junit.jupiter.api.Test;

class StoreTypeTest {

    @Test
    void blankMeansGeneralAndCaseDoesNotMatter() {
        assertThat(StoreType.parse(null)).isEqualTo(StoreType.GENERAL);
        assertThat(StoreType.parse(" ")).isEqualTo(StoreType.GENERAL);
        assertThat(StoreType.parse("fashion")).isEqualTo(StoreType.FASHION);
    }

    @Test
    void anUnknownTypeIsABadRequest() {
        assertThatThrownBy(() -> StoreType.parse("bakery"))
                .isInstanceOf(ApiException.class).hasMessage("type: unknown store type");
    }

    @Test
    void groceryPresetMatchesTheSpec() {
        StoreSettings s = StoreType.GROCERY.settings();
        assertThat(StoreType.GROCERY.features()).containsExactlyEntriesOf(java.util.Map.of("variants", false));
        assertThat(s.commerce().shippingFee()).isEqualTo(40.0);
        assertThat(s.commerce().freeShippingAbove()).isEqualTo(500.0);
        assertThat(s.commerce().defaultTaxRate()).isEqualTo(5.0);
        assertThat(s.commerce().stuckOrderDays()).isEqualTo(1);
        assertThat(s.catalog().lowStockThreshold()).isEqualTo(10);
    }

    @Test
    void generalSetsNothingAndNoPresetTouchesMarketing() {
        assertThat(StoreType.GENERAL.features()).isEmpty();
        assertThat(StoreType.GENERAL.settings()).isEqualTo(StoreSettings.NONE);
        for (StoreType t : StoreType.values()) {
            assertThat(t.features()).doesNotContainKey("marketing");
        }
    }
}
