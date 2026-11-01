package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Normalisation and SHA-256 per Meta's customer-information rules; vectors from Meta's docs and sha256sum. */
class PiiHasherTest {

    @Test
    void anEmailIsTrimmedLowerCasedThenHashed() {
        assertThat(PiiHasher.email("  Mary@Example.com ")).isEqualTo(
                "f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79");
        assertThat(PiiHasher.email(" ")).isNull();
        assertThat(PiiHasher.email(null)).isNull();
    }

    @Test
    void aPhoneBecomesDigitsWithItsCountryCode() {
        assertThat(PiiHasher.phone("+1 (555) 987-6543", "91")).isEqualTo(
                "1ef970831d7963307784fa8688e8fce101a15685d62aa765fed23f3a2c576a4e");
        String india = "92b5072176e723878b5e06ff3ca61898e4eb74e8c46642a0f2db800b17364ab0"; // sha256("919876543210")
        assertThat(PiiHasher.phone("98765 43210", "91")).isEqualTo(india);
        assertThat(PiiHasher.phone("098765-43210", "91")).isEqualTo(india);
        assertThat(PiiHasher.phone("+91 98765 43210", "91")).isEqualTo(india);
        assertThat(PiiHasher.phone("123", "91")).isNull();
        assertThat(PiiHasher.phone(null, "91")).isNull();
    }
}
