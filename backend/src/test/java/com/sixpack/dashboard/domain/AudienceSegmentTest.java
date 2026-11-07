package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.dashboard.domain.AudienceBands.Buyer;
import com.sixpack.dashboard.domain.Sections.Audience;
import com.sixpack.dashboard.domain.Sections.AudienceCell;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudienceSegmentTest {

    private static final LocalDate END = LocalDate.of(2026, 10, 31);
    private static final LocalDate SEP_END = LocalDate.of(2026, 9, 30);

    @Test
    void blankOrMissingMeansEveryone() {
        assertThat(AudienceSegment.of(null, null).isAll()).isTrue();
        assertThat(AudienceSegment.of(" ", "").isAll()).isTrue();
        assertThat(AudienceSegment.of("female", null).isAll()).isFalse();
        assertThat(AudienceSegment.of(null, "55+").isAll()).isFalse();
    }

    @Test
    void valuesAreReadIgnoringCaseAndSpaces() {
        AudienceSegment s = AudienceSegment.of(" Female ", " 25-34 ");
        assertThat(s.gender()).isEqualTo("female");
        assertThat(s.ageBand()).isEqualTo("25-34");
        assertThat(AudienceSegment.of("UNKNOWN", "Unknown")).isEqualTo(new AudienceSegment("unknown", "unknown"));
    }

    @Test
    void unknownValuesAreRejectedInTheSummaryStyle() {
        assertThatThrownBy(() -> AudienceSegment.of("other", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("gender: unknown value");
        assertThatThrownBy(() -> AudienceSegment.of(null, "55"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("ageBand: unknown value");
        assertThatThrownBy(() -> AudienceSegment.of("all", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("gender: unknown value");
        assertThatThrownBy(() -> AudienceSegment.of(null, "all"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("ageBand: unknown value");
    }

    @Test
    void aShownSegmentKeepsOnlyItsBuyers() {
        List<Buyer> buyers = new ArrayList<>();
        buyers.addAll(buyers("f", 6, "female", LocalDate.of(1995, 5, 5)));
        buyers.addAll(buyers("m", 6, "male", LocalDate.of(1995, 5, 5)));
        buyers.addAll(buyers("u", 2, null, null));
        buyers.addAll(buyers("o", 2, "other", null));
        buyers.addAll(buyers("g", 2, null, null)); // accounts that are gone: no gender, no birth date

        AudienceSegment.Match male = AudienceSegment.of("male", null).match(buyers, END);
        assertThat(male.hidden()).isFalse();
        assertThat(male.buyers()).containsExactlyInAnyOrder("m0", "m1", "m2", "m3", "m4", "m5");

        AudienceSegment.Match unknown = AudienceSegment.of("unknown", null).match(buyers, END);
        assertThat(unknown.hidden()).isFalse();
        assertThat(unknown.buyers()).containsExactlyInAnyOrder("u0", "u1", "o0", "o1", "g0", "g1");

        AudienceSegment.Match noAge = AudienceSegment.of(null, "unknown").match(buyers, END);
        assertThat(noAge.buyers()).containsExactlyInAnyOrder("u0", "u1", "o0", "o1", "g0", "g1");

        AudienceSegment.Match femaleYoung = AudienceSegment.of("female", "25-34").match(buyers, END);
        assertThat(femaleYoung.buyers()).containsExactlyInAnyOrder("f0", "f1", "f2", "f3", "f4", "f5");
    }

    @Test
    void fewerThanFiveBuyersIsHiddenWithNoBuyers() {
        List<Buyer> buyers = new ArrayList<>(buyers("f", 6, "female", LocalDate.of(1995, 5, 5)));
        buyers.addAll(buyers("m", 4, "male", LocalDate.of(1995, 5, 5)));

        AudienceSegment.Match male = AudienceSegment.of("male", null).match(buyers, END);
        assertThat(male.hidden()).isTrue();
        assertThat(male.buyers()).isEmpty();
        // nobody at all in a band: hidden too, so "none" and "a few" look the same
        assertThat(AudienceSegment.of(null, "55+").match(buyers, END).hidden()).isTrue();
    }

    @Test
    void aSegmentTheTableHidesIsHiddenEvenWithFiveOrMoreBuyers() {
        // female 6, male 6, unknown 2: the unknown row is small, so the table also hides the female total
        // (else the grand total minus female and male would give the 2 away)
        List<Buyer> buyers = new ArrayList<>(buyers("f", 6, "female", LocalDate.of(1995, 5, 5)));
        buyers.addAll(buyers("m", 6, "male", LocalDate.of(1995, 5, 5)));
        buyers.addAll(buyers("u", 2, null, LocalDate.of(1995, 5, 5)));
        Audience table = AudienceBands.table(buyers, END);
        assertThat(table.rows().get(0).total().small()).isTrue();

        assertThat(AudienceSegment.of("female", null).match(buyers, END).hidden()).isTrue();
        assertThat(AudienceSegment.of("unknown", null).match(buyers, END).hidden()).isTrue();
        assertThat(AudienceSegment.of("male", null).match(buyers, END).hidden()).isFalse();
        // so the only shown gender is male: everyone minus male is female and unknown together (8 buyers)
    }

    @Test
    void shownOrHiddenFollowsTheMatchingItemOfTheTable() {
        List<Buyer> buyers = new ArrayList<>(buyers("f", 9, "female", LocalDate.of(1995, 5, 5)));
        buyers.addAll(buyers("fy", 4, "female", LocalDate.of(2004, 2, 2)));
        buyers.addAll(buyers("m", 7, "male", LocalDate.of(1985, 1, 1)));
        buyers.addAll(buyers("mo", 5, "male", LocalDate.of(1960, 1, 1)));
        buyers.addAll(buyers("u", 3, null, null));
        buyers.addAll(buyers("uy", 6, null, LocalDate.of(1995, 5, 5)));
        Audience table = AudienceBands.table(buyers, END);
        List<String> genders = new ArrayList<>(AudienceBands.GENDERS);
        genders.add(null);
        List<String> bands = new ArrayList<>(AudienceBands.BANDS);
        bands.add(null);
        for (int g = 0; g < genders.size(); g++) {
            for (int b = 0; b < bands.size(); b++) {
                if (genders.get(g) == null && bands.get(b) == null) {
                    continue;
                }
                AudienceCell item = b < AudienceBands.BANDS.size() ? table.rows().get(g).cells().get(b)
                        : table.rows().get(g).total();
                AudienceSegment.Match m = new AudienceSegment(genders.get(g), bands.get(b)).match(buyers, END);
                assertThat(m.hidden()).as("%s / %s", genders.get(g), bands.get(b)).isEqualTo(item.small());
                if (!m.hidden()) {
                    assertThat((long) m.buyers().size()).as("%s / %s", genders.get(g), bands.get(b))
                            .isEqualTo(item.buyers());
                }
            }
        }
    }

    @Test
    void ageIsTakenAtTheGivenPeriodEnd() {
        // six turn 25 on 20 October: 25-34 at the end of October, 18-24 at the end of September
        List<Buyer> buyers = new ArrayList<>(buyers("b", 6, "female", LocalDate.of(2001, 10, 20)));
        buyers.addAll(buyers("o", 6, "female", LocalDate.of(1995, 5, 5)));
        AudienceSegment band = AudienceSegment.of(null, "25-34");
        assertThat(band.match(buyers, END).buyers()).hasSize(12);
        assertThat(band.match(buyers, SEP_END).buyers()).containsExactlyInAnyOrder("o0", "o1", "o2", "o3", "o4",
                "o5");
        assertThat(AudienceSegment.of(null, "18-24").match(buyers, SEP_END).buyers()).hasSize(6);
    }

    @Test
    void fileNamesSayWhichSegmentTheyHold() {
        assertThat(AudienceSegment.ALL.fileTag()).isEmpty();
        assertThat(AudienceSegment.of("female", null).fileTag()).isEqualTo("-female");
        assertThat(AudienceSegment.of(null, "55+").fileTag()).isEqualTo("-55+");
        assertThat(AudienceSegment.of("male", "25-34").fileTag()).isEqualTo("-male-25-34");
    }

    private static List<Buyer> buyers(String prefix, int n, String gender, LocalDate dob) {
        List<Buyer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Buyer(prefix + i, gender, dob, 1, 100));
        }
        return out;
    }
}
