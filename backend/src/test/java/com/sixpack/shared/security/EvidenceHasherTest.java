package com.sixpack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class EvidenceHasherTest {

    @Test
    void hashesWithTheSaltAndNeverReturnsTheInput() {
        var hasher = new EvidenceHasher("pepper");
        String hash = hasher.hash("203.0.113.9");

        assertThat(hash).hasSize(64).doesNotContain("203.0.113.9");
        assertThat(hasher.hash("203.0.113.9")).isEqualTo(hash);
        assertThat(new EvidenceHasher("other").hash("203.0.113.9")).isNotEqualTo(hash);
    }

    @Test
    void aBlankValueHashesToTheEmptyString() {
        var hasher = new EvidenceHasher("pepper");
        assertThat(hasher.hash(null)).isEmpty();
        assertThat(hasher.hash("  ")).isEmpty();
    }

    @Test
    void aBlankSaltDoesNotFailStartupAndStillHashes() {
        assertThatCode(() -> new EvidenceHasher("")).doesNotThrowAnyException();
        assertThatCode(() -> new EvidenceHasher(null)).doesNotThrowAnyException();

        String hash = new EvidenceHasher("").hash("203.0.113.9");
        assertThat(hash).hasSize(64).doesNotContain("203.0.113.9");
        assertThat(new EvidenceHasher(null).hash("203.0.113.9")).isEqualTo(hash);
    }
}
