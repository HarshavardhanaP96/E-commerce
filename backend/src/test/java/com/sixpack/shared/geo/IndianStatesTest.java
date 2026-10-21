package com.sixpack.shared.geo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IndianStatesTest {

    @Test
    void twentyEightStatesAndEightUnionTerritoriesSortedByName() {
        assertThat(IndianStates.all()).hasSize(36);
        assertThat(IndianStates.all().stream().filter(IndianStates.State::unionTerritory)).hasSize(8);
        assertThat(IndianStates.all().get(0).name()).isEqualTo("Andaman and Nicobar Islands");
        assertThat(IndianStates.all().stream().map(IndianStates.State::code).distinct()).hasSize(36);
    }

    @ParameterizedTest
    @CsvSource({
            "Karnataka, KA", "karnataka , KA", "KA, KA", "ka, KA", "Tamil Nadu, TN", "tamil   nadu, TN",
            "Tamil-Nadu, TN", "Tamilnadu, TN", "Orissa, OD", "OR, OD", "Pondicherry, PY", "Uttaranchal, UK",
            "UT, UK", "CT, CG", "TG, TS", "New Delhi, DL", "NCT of Delhi, DL", "J&K, JK", "Jammu & Kashmir, JK",
            "Daman and Diu, DH", "Dadra and Nagar Haveli, DH", "Andaman & Nicobar, AN"})
    void recognisesNamesCodesAndCommonSpellings(String raw, String code) {
        assertThat(IndianStates.normalise(raw)).map(IndianStates.State::code).contains(code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "Bangalore", "California", "XX"})
    void unknownIsEmpty(String raw) {
        assertThat(IndianStates.normalise(raw)).isEmpty();
    }

    @Test
    void codesLookUpTheOfficialName() {
        assertThat(IndianStates.byCode("ka")).map(IndianStates.State::name).contains("Karnataka");
        assertThat(IndianStates.byCode("ZZ")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"India", "india ", "IN", "IND", "Bharat"})
    void indiaSpelledAnyCommonWay(String country) {
        assertThat(IndianStates.isIndia(country)).isTrue();
    }

    @Test
    void otherCountriesAreNotIndia() {
        assertThat(IndianStates.isIndia("Nepal")).isFalse();
        assertThat(IndianStates.isIndia(null)).isFalse();
    }
}
