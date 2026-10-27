package com.sixpack.dashboard.domain;

import com.sixpack.dashboard.domain.AudienceBands.Buyer;
import com.sixpack.dashboard.domain.Sections.Audience;
import com.sixpack.dashboard.domain.Sections.AudienceCell;
import com.sixpack.dashboard.domain.Sections.AudienceRow;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The audience a Sales or Products view is narrowed to: a gender ({@code female}, {@code male}, {@code unknown}), an
 * age band (see {@link AudienceBands#BANDS}), both, or neither ({@link #ALL}). The one place the {@code gender} and
 * {@code ageBand} query parameters are read.
 *
 * <p>Privacy: a narrowed result is shown only when the matching item of the same period's audience table (same
 * buyers, same as-of day) is shown — the gender's row total, the band's column total, or the cell for both. The
 * table already hides every item that its sums could give away, so a narrowed result never tells more than the
 * table does about who is in a hidden group; subtracting it from the whole store or from other segments gives
 * only sums the table shows too.
 *
 * @param gender  null for any gender
 * @param ageBand null for any age
 */
public record AudienceSegment(String gender, String ageBand) {

    public static final AudienceSegment ALL = new AudienceSegment(null, null);

    /**
     * @param hidden true when the period's audience table hides the matching item: show no figures
     * @param buyers the user ids (as orders store them) of the period's buyers in the segment; empty when hidden
     */
    public record Match(boolean hidden, Set<String> buyers) {

        static final Match HIDDEN = new Match(true, Set.of());
    }

    public AudienceSegment {
        if (gender != null && !AudienceBands.GENDERS.contains(gender)) {
            throw new IllegalArgumentException("gender: unknown value");
        }
        if (ageBand != null && !AudienceBands.BANDS.contains(ageBand)) {
            throw new IllegalArgumentException("ageBand: unknown value");
        }
    }

    /**
     * Reads the query parameters, ignoring case and surrounding spaces; blank means any.
     *
     * @throws IllegalArgumentException "gender: unknown value" or "ageBand: unknown value", as {@code /summary} words
     *                                  a bad value
     */
    public static AudienceSegment of(String gender, String ageBand) {
        return new AudienceSegment(clean(gender), clean(ageBand));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isAll() {
        return gender == null && ageBand == null;
    }

    /**
     * Decides whether the segment may be shown for a period and, if so, which of its buyers belong to it.
     *
     * @param buyers every buyer of the period, as the audience table bins them (gender and birth date from their
     *               account; both null when the account is gone, which makes them unknown)
     * @param asOf   the period's last day: ages are taken on it
     */
    public Match match(List<Buyer> buyers, LocalDate asOf) {
        if (item(AudienceBands.table(buyers, asOf)).small()) {
            return Match.HIDDEN;
        }
        Set<String> ids = buyers.stream()
                .filter(b -> (gender == null || gender.equals(AudienceBands.gender(b.gender())))
                        && (ageBand == null || ageBand.equals(AudienceBands.band(b.dob(), asOf))))
                .map(Buyer::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Match(false, ids);
    }

    /** The table item this segment's figures equal: a cell, a row total, a column total, or the grand total. */
    private AudienceCell item(Audience table) {
        List<AudienceRow> rows = table.rows();
        AudienceRow row = gender == null ? rows.get(rows.size() - 1) : rows.get(AudienceBands.GENDERS.indexOf(gender));
        return ageBand == null ? row.total() : row.cells().get(table.bands().indexOf(ageBand));
    }

    /** For file names: "" for everyone, else "-female", "-25-34" or "-female-25-34". */
    public String fileTag() {
        return (gender == null ? "" : "-" + gender) + (ageBand == null ? "" : "-" + ageBand);
    }
}
