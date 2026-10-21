package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.dashboard.domain.LocationReads.RegionCount;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocationSectionTest {

    @Test
    void mergesSpellingsOfTheSameStateAndFlagsSmallGroups() {
        List<LocationSection.RegionRow> rows = LocationSection.rows(
                List.of(new RegionCount("Karnataka", 1, 1000), new RegionCount("karnataka ", 1, 500),
                        new RegionCount("KA", 1, 300), new RegionCount("Tamil Nadu", 1, 200),
                        new RegionCount("Bangalore", 1, 100)),
                List.of(new RegionCount("Karnataka", 1, 400)),
                LocationSection::stateKey, LocationSection::stateLabel);
        assertThat(rows).extracting(LocationSection.RegionRow::key).containsExactly("KA", "TN", "unrecognised");
        LocationSection.RegionRow ka = rows.get(0);
        assertThat(ka.label()).isEqualTo("Karnataka");
        assertThat(ka.orders()).isEqualTo(3);
        assertThat(ka.revenue()).isEqualTo(1800);
        assertThat(ka.averageOrderValue()).isEqualTo(600);
        assertThat(ka.share()).isEqualTo(60.0);
        assertThat(ka.change()).isEqualTo(200L);
        assertThat(ka.small()).isFalse();
        assertThat(rows.get(1).small()).isTrue();
        assertThat(rows.get(1).change()).isNull();
    }

    @Test
    void unrecognisedStatesKeepTheirRawSpellingForTheShopToSee() {
        LocationSection.Unrecognised u = LocationSection.unrecognised(List.of(new RegionCount("Bangalore", 2, 100),
                new RegionCount("KA", 1, 300), new RegionCount(null, 1, 10)));
        assertThat(u.orders()).isEqualTo(3);
        assertThat(u.samples()).extracting(LocationSection.Sample::raw).containsExactly("Bangalore", "(blank)");
    }

    @Test
    void countryAndPinKeys() {
        assertThat(LocationSection.countryKey(" india")).isEqualTo("india");
        assertThat(LocationSection.countryKey(null)).isEqualTo("unknown");
        assertThat(LocationSection.countryLabel("india")).isEqualTo("India");
        assertThat(LocationSection.countryLabel("sri lanka")).isEqualTo("Sri Lanka");
        assertThat(LocationSection.pinKey(RegionLevel.PIN_PREFIX, "560")).isEqualTo("560");
        assertThat(LocationSection.pinKey(RegionLevel.PIN_PREFIX, "4")).isEqualTo("invalid");
        assertThat(LocationSection.pinKey(RegionLevel.PIN, null)).isEqualTo("unknown");
    }

    @Test
    void theFilterPicksTheLevelAndCsvStopsAtThePrefix() {
        assertThat(new LocationSection.Filter(null, null, null).level(false)).isEqualTo(RegionLevel.COUNTRY);
        assertThat(new LocationSection.Filter("India", null, null).level(false)).isEqualTo(RegionLevel.STATE);
        assertThat(new LocationSection.Filter("India", "KA", null).level(false)).isEqualTo(RegionLevel.PIN_PREFIX);
        assertThat(new LocationSection.Filter("India", "KA", "560").level(false)).isEqualTo(RegionLevel.PIN);
        assertThat(new LocationSection.Filter("India", "KA", "560").level(true)).isEqualTo(RegionLevel.PIN_PREFIX);
    }

    @Test
    void theFilterRejectsDrillsThatMakeNoSense() {
        assertThatThrownBy(() -> new LocationSection.Filter(null, "KA", null))
                .hasMessage("state: choose India as the country first");
        assertThatThrownBy(() -> new LocationSection.Filter("Nepal", "KA", null))
                .hasMessage("state: choose India as the country first");
        assertThatThrownBy(() -> new LocationSection.Filter("India", "ZZ", null)).hasMessage("state: unknown state code");
        assertThatThrownBy(() -> new LocationSection.Filter("India", "KA", "56")).hasMessage("pinPrefix: three digits");
        assertThatThrownBy(() -> new LocationSection.Filter("India", null, "560"))
                .hasMessage("pinPrefix: choose a state first");
    }

    @Test
    void noOrdersNoRows() {
        assertThat(LocationSection.rows(List.of(), null, LocationSection::countryKey, LocationSection::countryLabel))
                .isEmpty();
    }
}
