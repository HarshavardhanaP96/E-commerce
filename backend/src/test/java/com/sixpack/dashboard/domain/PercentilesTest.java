package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PercentilesTest {

    @Test
    void nearestRank() {
        List<Double> v = List.of(48.0, 24.0, 72.0, 12.0);
        assertThat(Percentiles.of(v, 50)).isEqualTo(24.0);
        assertThat(Percentiles.of(v, 90)).isEqualTo(72.0);
        assertThat(Percentiles.of(List.of(5.0), 90)).isEqualTo(5.0);
    }

    @Test
    void nothingToMeasureIsNull() {
        assertThat(Percentiles.of(List.of(), 50)).isNull();
    }
}
