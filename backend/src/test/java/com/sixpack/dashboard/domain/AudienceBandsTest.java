package com.sixpack.dashboard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.dashboard.domain.AudienceBands.Buyer;
import com.sixpack.dashboard.domain.Sections.Audience;
import com.sixpack.dashboard.domain.Sections.AudienceCell;
import com.sixpack.dashboard.domain.Sections.AudienceRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
        Audience five = AudienceBands.table(buyers(5, "female", LocalDate.of(1995, 5, 5), 2, 300), END);
        assertThat(cell(five, "female", "25-34")).isEqualTo(new AudienceCell(5L, 10L, 1500.0, 100.0, false));
        // nobody there: small too, so an empty cell cannot be told from a small one
        assertThat(cell(five, "female", "55+")).isEqualTo(SMALL);

        Audience four = AudienceBands.table(buyers(4, "female", LocalDate.of(1995, 5, 5), 2, 300), END);
        assertThat(cell(four, "female", "25-34")).isEqualTo(SMALL);
        assertThat(row(four, "female").total()).isEqualTo(SMALL);
        assertThat(cell(four, "all", "25-34")).isEqualTo(SMALL);
        // the grand total is the Customers count: always shown
        assertThat(row(four, "all").total()).isEqualTo(new AudienceCell(4L, 8L, 1200.0, 100.0, false));
    }

    @Test
    void rowsAreTheGendersThenTheTotalAndCellsFollowTheBands() {
        Audience a = AudienceBands.table(List.of(), END);
        assertThat(a.asOf()).isEqualTo(END);
        assertThat(a.bands()).isEqualTo(AudienceBands.BANDS);
        assertThat(a.rows()).extracting(AudienceRow::gender).containsExactly("female", "male", "unknown", "all");
        assertThat(a.rows()).allSatisfy(r -> assertThat(r.cells()).hasSize(6).containsOnly(SMALL));
        assertThat(a.rows().subList(0, 3)).allSatisfy(r -> assertThat(r.total()).isEqualTo(SMALL));
        assertThat(row(a, "all").total()).isEqualTo(new AudienceCell(0L, 0L, 0.0, 0.0, false));
    }

    @Test
    void totalsAddUpAcrossBandsAndGenders() {
        List<Buyer> all = new ArrayList<>();
        all.addAll(buyers(5, "female", LocalDate.of(2004, 1, 1), 1, 100)); // 18-24
        all.addAll(buyers(6, "female", LocalDate.of(1990, 1, 1), 1, 100)); // 35-44
        all.addAll(buyers(5, "male", LocalDate.of(2004, 1, 1), 2, 100));
        all.addAll(buyers(5, "male", LocalDate.of(1990, 1, 1), 1, 100));
        Audience a = AudienceBands.table(all, END);

        assertThat(row(a, "female").total()).isEqualTo(new AudienceCell(11L, 11L, 1100.0, 52.4, false));
        assertThat(row(a, "male").total()).isEqualTo(new AudienceCell(10L, 15L, 1000.0, 47.6, false));
        assertThat(cell(a, "all", "18-24")).isEqualTo(new AudienceCell(10L, 15L, 1000.0, 47.6, false));
        assertThat(cell(a, "all", "35-44")).isEqualTo(new AudienceCell(11L, 11L, 1100.0, 52.4, false));
        assertThat(row(a, "all").total()).isEqualTo(new AudienceCell(21L, 26L, 2100.0, 100.0, false));
        // empty cells and an empty row are small, but give nothing away: nothing else is hidden
        assertThat(row(a, "unknown").total()).isEqualTo(SMALL);
        assertThat(cell(a, "female", "18-24").small()).isFalse();
    }

    @Test
    void totalsAreThresholdedThemselves() {
        List<Buyer> all = new ArrayList<>();
        all.addAll(buyers(3, "female", LocalDate.of(2004, 1, 1), 1, 100)); // 18-24
        all.addAll(buyers(3, "female", LocalDate.of(1990, 1, 1), 1, 100)); // 35-44
        Audience a = AudienceBands.table(all, END);

        assertThat(cell(a, "female", "18-24")).isEqualTo(SMALL);
        assertThat(cell(a, "female", "35-44")).isEqualTo(SMALL);
        assertThat(cell(a, "all", "18-24")).isEqualTo(SMALL);
        assertThat(cell(a, "all", "35-44")).isEqualTo(SMALL);
        // six buyers across two small cells: the row total shows
        assertThat(row(a, "female").total()).isEqualTo(new AudienceCell(6L, 6L, 600.0, 100.0, false));
        assertNothingGivenAway(a, new int[][] {{3, 0, 3, 0, 0, 0}, {0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0}});
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

    // ---------- complementary suppression: totals must not give a hidden group away ----------

    /** A birth date in each band on END, unknown last. */
    private static final LocalDate[] BAND_DOB = {LocalDate.of(2004, 1, 1), LocalDate.of(1995, 1, 1),
            LocalDate.of(1985, 1, 1), LocalDate.of(1975, 1, 1), LocalDate.of(1960, 1, 1), null};

    /** {@code counts[gender][band]} buyers, each with one order of 100. */
    private static Audience table(int[][] counts) {
        List<Buyer> all = new ArrayList<>();
        for (int g = 0; g < 3; g++) {
            for (int b = 0; b < 6; b++) {
                all.addAll(buyers(counts[g][b], AudienceBands.GENDERS.get(g), BAND_DOB[b], 1, 100));
            }
        }
        return AudienceBands.table(all, END);
    }

    private static boolean[][] shown(Audience a) {
        boolean[][] out = new boolean[4][7];
        for (int g = 0; g < 4; g++) {
            AudienceRow r = a.rows().get(g);
            for (int b = 0; b < 6; b++) {
                out[g][b] = !r.cells().get(b).small();
            }
            out[g][6] = !r.total().small();
        }
        return out;
    }

    @Test
    void theRowTotalsNoLongerGiveTheUnknownRowAway() {
        // the September shape: grand total 18 − female 9 − male 5 used to give unknown's 4 buyers exactly
        Audience a = table(new int[][] {{4, 5, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 5}, {0, 0, 1, 0, 0, 3}});

        assertThat(row(a, "unknown").total().small()).isTrue();
        assertThat(row(a, "male").total()).isEqualTo(SMALL);
        // female 25-34 would give away female 18-24 (9 − 5), male / age unknown would give away unknown / unknown
        assertThat(cell(a, "female", "25-34")).isEqualTo(SMALL);
        assertThat(cell(a, "male", "unknown")).isEqualTo(SMALL);
        assertThat(row(a, "female").total()).isEqualTo(new AudienceCell(9L, 9L, 900.0, 50.0, false));
        assertThat(cell(a, "all", "25-34")).isEqualTo(new AudienceCell(5L, 5L, 500.0, 27.8, false));
        assertThat(cell(a, "all", "unknown")).isEqualTo(new AudienceCell(8L, 8L, 800.0, 44.4, false));
        assertThat(row(a, "all").total()).isEqualTo(new AudienceCell(18L, 18L, 1800.0, 100.0, false));
        assertNothingGivenAway(a, new int[][] {{4, 5, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 5}, {0, 0, 1, 0, 0, 3}});
    }

    @Test
    void theGrandTotalIsNeverHidden() {
        Audience a = table(new int[][] {{1, 0, 0, 0, 0, 0}, {0, 2, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0}});
        assertThat(row(a, "all").total()).isEqualTo(new AudienceCell(3L, 3L, 300.0, 100.0, false));
        assertThat(a.rows()).allSatisfy(r -> assertThat(r.cells()).containsOnly(SMALL));
    }

    @Test
    void aTableWithNoSmallGroupsHidesNothing() {
        Audience a = table(new int[][] {{5, 6, 7, 8, 9, 10}, {5, 5, 5, 5, 5, 5}, {6, 6, 6, 6, 6, 6}});
        assertThat(a.rows()).allSatisfy(r -> {
            assertThat(r.cells()).noneMatch(AudienceCell::small);
            assertThat(r.total().small()).isFalse();
        });
    }

    @Test
    void noSumOrHiddenGroupCanBeWorkedOutInVariedTables() {
        List<int[][]> tables = new ArrayList<>(List.of(
                new int[][] {{4, 5, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 5}, {0, 0, 1, 0, 0, 3}},
                new int[][] {{3, 6, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0}},
                new int[][] {{1, 20, 20, 20, 20, 20}, {20, 20, 20, 20, 20, 20}, {20, 20, 20, 20, 20, 20}},
                new int[][] {{12, 7, 1, 9, 30, 4}, {8, 2, 15, 6, 0, 11}, {3, 9, 0, 1, 4, 22}},
                new int[][] {{0, 0, 0, 0, 0, 7}, {0, 0, 0, 0, 0, 2}, {0, 0, 0, 0, 0, 40}}));
        Random random = new Random(19);
        int[] choices = {0, 0, 0, 1, 2, 3, 4, 5, 6, 8, 12, 25};
        for (int t = 0; t < 300; t++) {
            int[][] counts = new int[3][6];
            for (int[] row : counts) {
                for (int b = 0; b < 6; b++) {
                    row[b] = choices[random.nextInt(choices.length)];
                }
            }
            tables.add(counts);
        }
        for (int[][] counts : tables) {
            assertNothingGivenAway(table(counts), counts);
        }
    }

    /**
     * Checks one table against the attack the rule defends: the grand total is public and every shown figure is
     * known. (1) For every sum (a row or column against its total), true total − shown parts is 0 or at least 5
     * buyers; a sum with no part shown is left to the sum its total is part of. (2) No hidden item, pair of hidden
     * items or sum's hidden parts with 1-4 buyers is pinned down exactly by the table's equations.
     */
    private static void assertNothingGivenAway(Audience a, int[][] counts) {
        int[][] truth = new int[4][7];
        for (int g = 0; g < 3; g++) {
            for (int b = 0; b < 6; b++) {
                truth[g][b] = counts[g][b];
                truth[g][6] += counts[g][b];
                truth[3][b] += counts[g][b];
                truth[3][6] += counts[g][b];
            }
        }
        boolean[][] shown = shown(a);
        String where = java.util.Arrays.deepToString(counts);
        assertThat(shown[3][6]).as("grand total shown in %s", where).isTrue();
        for (int g = 0; g < 4; g++) {
            for (int b = 0; b < 7; b++) {
                AudienceCell c = b < 6 ? a.rows().get(g).cells().get(b) : a.rows().get(g).total();
                if (shown[g][b]) {
                    assertThat(c.buyers()).as("buyers at %d,%d in %s", g, b, where).isEqualTo((long) truth[g][b]);
                } else {
                    assertThat(c).isEqualTo(SMALL);
                }
            }
        }
        List<int[][]> sums = sums();
        for (int[][] sum : sums) {
            int residual = truth[sum[0][0]][sum[0][1]];
            boolean anyShown = false;
            for (int i = 1; i < sum.length; i++) {
                if (shown[sum[i][0]][sum[i][1]]) {
                    anyShown = true;
                    residual -= truth[sum[i][0]][sum[i][1]];
                }
            }
            if (anyShown) {
                assertThat(residual).as("residual of sum at %d,%d in %s", sum[0][0], sum[0][1], where)
                        .satisfiesAnyOf(r -> assertThat(r).isZero(), r -> assertThat(r).isGreaterThanOrEqualTo(5));
            }
        }

        // hidden items as unknowns; each sum is one equation over them
        List<int[]> hidden = new ArrayList<>();
        for (int g = 0; g < 4; g++) {
            for (int b = 0; b < 7; b++) {
                if (!shown[g][b]) {
                    hidden.add(new int[] {g, b});
                }
            }
        }
        List<double[]> equations = new ArrayList<>();
        for (int[][] sum : sums) {
            double[] eq = new double[hidden.size()];
            for (int i = 0; i < sum.length; i++) {
                int at = indexOf(hidden, sum[i]);
                if (at >= 0) {
                    eq[at] += i == 0 ? -1 : 1;
                }
            }
            equations.add(eq);
        }
        int rank = rank(equations);
        List<double[]> groups = new ArrayList<>();
        for (int i = 0; i < hidden.size(); i++) {
            for (int j = i; j < hidden.size(); j++) {
                double[] w = new double[hidden.size()];
                w[i] = 1;
                w[j] = 1;
                groups.add(w);
            }
        }
        for (int[][] sum : sums) {
            double[] w = new double[hidden.size()];
            for (int i = 1; i < sum.length; i++) {
                int at = indexOf(hidden, sum[i]);
                if (at >= 0) {
                    w[at] = 1;
                }
            }
            groups.add(w);
        }
        for (double[] w : groups) {
            int buyers = 0;
            for (int i = 0; i < w.length; i++) {
                buyers += (int) w[i] * truth[hidden.get(i)[0]][hidden.get(i)[1]];
            }
            if (buyers >= 1 && buyers < AudienceBands.MIN_BUYERS) {
                List<double[]> with = new ArrayList<>(equations);
                with.add(w);
                assertThat(rank(with)).as("a hidden group of %d buyers is pinned down in %s", buyers, where)
                        .isGreaterThan(rank);
            }
        }
    }

    /** Each sum as {total, part, part, …}: the gender rows, the all row, the band columns, the total column. */
    private static List<int[][]> sums() {
        List<int[][]> out = new ArrayList<>();
        for (int g = 0; g < 4; g++) {
            int[][] sum = new int[7][];
            sum[0] = new int[] {g, 6};
            for (int b = 0; b < 6; b++) {
                sum[b + 1] = new int[] {g, b};
            }
            out.add(sum);
        }
        for (int b = 0; b < 7; b++) {
            out.add(new int[][] {{3, b}, {0, b}, {1, b}, {2, b}});
        }
        return out;
    }

    private static int indexOf(List<int[]> items, int[] item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i)[0] == item[0] && items.get(i)[1] == item[1]) {
                return i;
            }
        }
        return -1;
    }

    private static int rank(List<double[]> rows) {
        double[][] m = rows.stream().map(double[]::clone).toArray(double[][]::new);
        int rank = 0;
        int cols = m.length == 0 ? 0 : m[0].length;
        for (int c = 0; c < cols && rank < m.length; c++) {
            int pivot = -1;
            for (int r = rank; r < m.length; r++) {
                if (Math.abs(m[r][c]) > 1e-9) {
                    pivot = r;
                    break;
                }
            }
            if (pivot < 0) {
                continue;
            }
            double[] tmp = m[pivot];
            m[pivot] = m[rank];
            m[rank] = tmp;
            for (int r = 0; r < m.length; r++) {
                if (r != rank && Math.abs(m[r][c]) > 1e-9) {
                    double f = m[r][c] / m[rank][c];
                    for (int k = c; k < cols; k++) {
                        m[r][k] -= f * m[rank][k];
                    }
                }
            }
            rank++;
        }
        return rank;
    }
}
