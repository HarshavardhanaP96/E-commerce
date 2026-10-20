package com.sixpack.dashboard.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void writesAHeaderThenOneLinePerRow() {
        String csv = CsvWriter.write(List.of("start", "revenue"),
                List.of(List.of(LocalDate.of(2026, 10, 1), 1500.0), List.of(LocalDate.of(2026, 10, 2), 0.5)));
        assertThat(csv).isEqualTo("start,revenue\r\n2026-10-01,1500\r\n2026-10-02,0.5\r\n");
    }

    @Test
    void quotesCommasQuotesAndLineBreaks() {
        String csv = CsvWriter.write(List.of("name"), List.of(List.of("Shoe, \"red\""), List.of("two\nlines")));
        assertThat(csv).isEqualTo("name\r\n\"Shoe, \"\"red\"\"\"\r\n\"two\nlines\"\r\n");
    }

    @Test
    void neutralisesSpreadsheetFormulasButNotNegativeNumbers() {
        String csv = CsvWriter.write(List.of("a", "b", "c", "d", "e"),
                List.of(List.of("=SUM(A1)", "+1", "@cmd", "-x", -5)));
        assertThat(csv).isEqualTo("a,b,c,d,e\r\n'=SUM(A1),'+1,'@cmd,'-x,-5\r\n");
    }

    @Test
    void nullsAreEmptyAndInstantsAreIso() {
        String csv = CsvWriter.write(List.of("a", "b"),
                List.of(Arrays.asList(null, Instant.parse("2026-10-05T10:00:00Z"))));
        assertThat(csv).isEqualTo("a,b\r\n,2026-10-05T10:00:00Z\r\n");
    }

    @Test
    void wholeNumbersHaveNoTrailingZeros() {
        assertThat(CsvWriter.write(List.of("n"), List.of(List.of(3L), List.of(2.50)))).isEqualTo("n\r\n3\r\n2.5\r\n");
    }
}
