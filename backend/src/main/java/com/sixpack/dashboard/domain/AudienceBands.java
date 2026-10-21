package com.sixpack.dashboard.domain;

import com.sixpack.dashboard.domain.Sections.Audience;
import com.sixpack.dashboard.domain.Sections.AudienceCell;
import com.sixpack.dashboard.domain.Sections.AudienceRow;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bins the period's buyers by gender and age band into the audience table (see {@link Audience}). Any cell or
 * total with fewer than {@link #MIN_BUYERS} distinct buyers is {@code small}, its figures null. A fixed analysis
 * rule, like trending's minimum, not a store setting.
 */
public final class AudienceBands {

    public static final int MIN_BUYERS = 5;
    public static final String UNKNOWN = "unknown";
    /** The gender of the total row and, in the CSV, the band of each row's total. */
    public static final String ALL = "all";
    public static final List<String> BANDS = List.of("18-24", "25-34", "35-44", "45-54", "55+", UNKNOWN);
    public static final List<String> GENDERS = List.of("female", "male", UNKNOWN);

    /**
     * One buyer's orders in the period.
     *
     * @param gender as stored on the account (null when not given, erased or the account is gone)
     * @param dob    the birth date, null when not given
     */
    public record Buyer(String userId, String gender, LocalDate dob, long orders, double revenue) {
    }

    private AudienceBands() {
    }

    /** female, male, or unknown for anything else (gender is optional at sign-up). */
    public static String gender(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String g = raw.trim().toLowerCase(Locale.ROOT);
        return GENDERS.contains(g) ? g : UNKNOWN;
    }

    /**
     * The age band in whole years on {@code asOf}; unknown without a birth date, under 18 (sign-up enforces 18+,
     * so it is bad data) or with a birth date after {@code asOf}.
     */
    public static String band(LocalDate dob, LocalDate asOf) {
        if (dob == null || dob.isAfter(asOf)) {
            return UNKNOWN;
        }
        long age = ChronoUnit.YEARS.between(dob, asOf);
        if (age < 18) {
            return UNKNOWN;
        }
        return age <= 24 ? "18-24" : age <= 34 ? "25-34" : age <= 44 ? "35-44" : age <= 54 ? "45-54" : "55+";
    }

    /** Rows female, male, unknown, then all; each with one cell per band and its total. */
    public static Audience table(List<Buyer> buyers, LocalDate asOf) {
        int g = GENDERS.size();
        int b = BANDS.size();
        // [gender, or g for all][band, or b for all] -> buyers, orders, revenue
        double[][][] sums = new double[g + 1][b + 1][3];
        double revenue = 0;
        for (Buyer buyer : buyers) {
            int gi = GENDERS.indexOf(gender(buyer.gender()));
            int bi = BANDS.indexOf(band(buyer.dob(), asOf));
            for (int[] at : new int[][] {{gi, bi}, {gi, b}, {g, bi}, {g, b}}) {
                double[] cell = sums[at[0]][at[1]];
                cell[0]++;
                cell[1] += buyer.orders();
                cell[2] += buyer.revenue();
            }
            revenue += buyer.revenue();
        }
        List<AudienceRow> rows = new ArrayList<>();
        for (int gi = 0; gi <= g; gi++) {
            List<AudienceCell> cells = new ArrayList<>();
            for (int bi = 0; bi < b; bi++) {
                cells.add(cell(sums[gi][bi], revenue));
            }
            rows.add(new AudienceRow(gi < g ? GENDERS.get(gi) : ALL, List.copyOf(cells), cell(sums[gi][b], revenue)));
        }
        return new Audience(asOf, BANDS, List.copyOf(rows));
    }

    private static AudienceCell cell(double[] sum, double revenue) {
        if (sum[0] < MIN_BUYERS) {
            return new AudienceCell(null, null, null, null, true);
        }
        return new AudienceCell((long) sum[0], (long) sum[1], sum[2], Sections.percent(sum[2], revenue), false);
    }
}
