package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.AudienceBands.Buyer;
import com.sixpack.dashboard.domain.Sections.Audience;
import com.sixpack.dashboard.domain.Sections.AudienceCell;
import com.sixpack.dashboard.domain.Sections.AudienceRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudienceBandsTest {

    private static final LocalDate END = LocalDate.of(2026, 10, 31);

    @Test
    void genderIsFemaleMaleOrUnknown() {
        assertThat(AudienceBands.gender("female")).isEqualTo("female");
        assertThat(AudienceBands.gender(" MALE ")).isEqualTo("male");
        assertThat(AudienceBands.gender("Female")).isEqualTo("female");
        assertThat(AudienceBands.gender(null)).isEqualTo("unknown");
        assertThat(AudienceBands.gender("")).isEqualTo("unknown");
        assertThat(AudienceBands.gender("other")).isEqualTo("unknown");
    }

    @Test
    void aBirthdayOnThePeriodEndCountsThatDay() {
        // 25 on the last day of the period: 25-34, though 24 for the rest of it
        assertThat(AudienceBands.band(LocalDate.of(2001, 10, 31), END)).isEqualTo("25-34");
        // 25 the day after the period ends: still 24
        assertThat(AudienceBands.band(LocalDate.of(2001, 11, 1), END)).isEqualTo("18-24");
    }

    @Test
    void bandEdges() {
        assertThat(AudienceBands.band(LocalDate.of(2008, 10, 31), END)).isEqualTo("18-24"); // 18 exactly
        assertThat(AudienceBands.band(LocalDate.of(2002, 1, 1), END)).isEqualTo("18-24"); // 24
        assertThat(AudienceBands.band(LocalDate.of(2001, 1, 1), END)).isEqualTo("25-34"); // 25
        assertThat(AudienceBands.band(LocalDate.of(1992, 1, 1), END)).isEqualTo("25-34"); // 34
        assertThat(AudienceBands.band(LocalDate.of(1991, 1, 1), END)).isEqualTo("35-44"); // 35
        assertThat(AudienceBands.band(LocalDate.of(1982, 1, 1), END)).isEqualTo("35-44"); // 44
        assertThat(AudienceBands.band(LocalDate.of(1981, 1, 1), END)).isEqualTo("45-54"); // 45
        assertThat(AudienceBands.band(LocalDate.of(1972, 1, 1), END)).isEqualTo("45-54"); // 54
        assertThat(AudienceBands.band(LocalDate.of(1971, 1, 1), END)).isEqualTo("55+"); // 55
        assertThat(AudienceBands.band(LocalDate.of(1930, 1, 1), END)).isEqualTo("55+");
    }

    @Test
    void noBirthDateUnderEighteenOrAFutureBirthDateIsUnknown() {
        assertThat(AudienceBands.band(null, END)).isEqualTo("unknown");
        assertThat(AudienceBands.band(LocalDate.of(2008, 11, 1), END)).isEqualTo("unknown"); // 17
        assertThat(AudienceBands.band(LocalDate.of(2027, 1, 1), END)).isEqualTo("unknown");
        assertThat(AudienceBands.band(END, END)).isEqualTo("unknown"); // born today
    }

    @Test
    void theBandsAreInOrderWithUnknownLast() {
        assertThat(AudienceBands.BANDS).containsExactly("18-24", "25-34", "35-44", "45-54", "55+", "unknown");
        assertThat(AudienceBands.GENDERS).containsExactly("female", "male", "unknown");
        assertThat(AudienceBands.MIN_BUYERS).isEqualTo(5);
    }

    private static List<Buyer> buyers(int count, String gender, LocalDate dob, long orders, double revenue) {
        List<Buyer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new Buyer(gender + dob + i, gender, dob, orders, revenue));
        }
        return out;
    }

    private static AudienceRow row(Audience a, String gender) {
        return a.rows().stream().filter(r -> r.gender().equals(gender)).findFirst().orElseThrow();
    }

    private static AudienceCell cell(Audience a, String gender, String band) {
        return row(a, gender).cells().get(a.bands().indexOf(band));
    }

    private static final AudienceCell SMALL = new AudienceCell(null, null, null, null, true);

    @Test
    void fiveBuyersShowTheirFiguresFourAreSmall() {
        List<Buyer> all = new ArrayList<>(buyers(5, "female", LocalDate.of(1995, 5, 5), 2, 300));
        all.addAll(buyers(4, "male", LocalDate.of(1995, 5, 5), 1, 100));
        Audience a = AudienceBands.table(all, END);

        assertThat(cell(a, "female", "25-34")).isEqualTo(new AudienceCell(5L, 10L, 1500.0, 78.9, false));
        assertThat(cell(a, "male", "25-34")).isEqualTo(SMALL);
        // nobody there: small too, so an empty cell cannot be told from a small one
        assertThat(cell(a, "female", "55+")).isEqualTo(SMALL);
    }

    @Test
    void rowsAreTheGendersThenTheTotalAndCellsFollowTheBands() {
        Audience a = AudienceBands.table(List.of(), END);
        assertThat(a.asOf()).isEqualTo(END);
        assertThat(a.bands()).isEqualTo(AudienceBands.BANDS);
        assertThat(a.rows()).extracting(AudienceRow::gender).containsExactly("female", "male", "unknown", "all");
        assertThat(a.rows()).allSatisfy(r -> {
            assertThat(r.cells()).hasSize(6).containsOnly(SMALL);
            assertThat(r.total()).isEqualTo(SMALL);
        });
    }

    @Test
    void totalsAddUpAcrossBandsAndGendersAndAreThresholdedThemselves() {
        List<Buyer> all = new ArrayList<>();
        all.addAll(buyers(3, "female", LocalDate.of(2004, 1, 1), 1, 100)); // 18-24
        all.addAll(buyers(3, "female", LocalDate.of(1990, 1, 1), 1, 100)); // 35-44
        all.addAll(buyers(2, "male", LocalDate.of(2004, 1, 1), 1, 100)); // 18-24
        all.addAll(buyers(1, null, null, 2, 200)); // unknown, unknown
        Audience a = AudienceBands.table(all, END);

        // the female row: two small cells, but six buyers in all
        assertThat(cell(a, "female", "18-24")).isEqualTo(SMALL);
        assertThat(cell(a, "female", "35-44")).isEqualTo(SMALL);
        assertThat(row(a, "female").total()).isEqualTo(new AudienceCell(6L, 6L, 600.0, 60.0, false));
        assertThat(row(a, "male").total()).isEqualTo(SMALL);
        assertThat(row(a, "unknown").total()).isEqualTo(SMALL);
        // the 18-24 column: five buyers across two small cells
        assertThat(cell(a, "all", "18-24")).isEqualTo(new AudienceCell(5L, 5L, 500.0, 50.0, false));
        assertThat(cell(a, "all", "35-44")).isEqualTo(SMALL);
        assertThat(cell(a, "all", "unknown")).isEqualTo(SMALL);
        assertThat(row(a, "all").total()).isEqualTo(new AudienceCell(9L, 10L, 1000.0, 100.0, false));
    }

    @Test
    void unknownGenderAndBirthDateLandInTheUnknownRowAndColumn() {
        List<Buyer> all = new ArrayList<>();
        all.addAll(buyers(2, null, null, 1, 100));
        all.addAll(buyers(2, "other", LocalDate.of(2010, 1, 1), 1, 100)); // under 18
        all.addAll(buyers(1, "", LocalDate.of(2030, 1, 1), 1, 100)); // future birth date
        Audience a = AudienceBands.table(all, END);

        assertThat(cell(a, "unknown", "unknown")).isEqualTo(new AudienceCell(5L, 5L, 500.0, 100.0, false));
        assertThat(row(a, "unknown").total()).isEqualTo(new AudienceCell(5L, 5L, 500.0, 100.0, false));
    }

    @Test
    void sharesAreOfThePeriodsRevenueAndZeroWhenThereIsNone() {
        Audience a = AudienceBands.table(buyers(5, "male", LocalDate.of(1960, 1, 1), 1, 0), END);
        assertThat(cell(a, "male", "55+")).isEqualTo(new AudienceCell(5L, 5L, 0.0, 0.0, false));
    }
}
