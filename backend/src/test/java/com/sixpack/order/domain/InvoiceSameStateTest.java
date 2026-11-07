package com.sixpack.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The seller is always in India (the store's GST registration is Indian); the buyer's country is on the address. */
class InvoiceSameStateTest {

    @Test
    void comparesStatesNotSpellings() {
        assertThat(Invoice.sameState("Karnataka", "KA", "India")).isTrue();
        assertThat(Invoice.sameState("karnataka ", "Karnataka", "india")).isTrue();
        assertThat(Invoice.sameState("Karnataka", "Tamil Nadu", "India")).isFalse();
    }

    @Test
    void aStateCodeAbroadIsNotAnIndianState() {
        // US "TN" is Tennessee
        assertThat(Invoice.sameState("Tamil Nadu", "TN", "United States")).isFalse();
    }

    @Test
    void fallsBackToTextForAnythingElse() {
        assertThat(Invoice.sameState("Somewhere", "somewhere ", "India")).isTrue();
        assertThat(Invoice.sameState("Somewhere", "somewhere ", "Nepal")).isTrue();
        assertThat(Invoice.sameState(null, "KA", "India")).isFalse();
        assertThat(Invoice.sameState(" ", " ", "India")).isFalse();
        assertThat(Invoice.sameState("Karnataka", "KA", null)).isFalse();
    }
}
