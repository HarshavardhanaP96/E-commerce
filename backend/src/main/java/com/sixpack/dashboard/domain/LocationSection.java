package com.sixpack.dashboard.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sixpack.dashboard.domain.LocationReads.RegionCount;
import com.sixpack.shared.geo.IndianStates;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where orders ship to, at one drill level. Groups with fewer than {@link #SMALL} orders are flagged
 * {@code small} and get no change figure. {@code unrecognised} is set at the state level only.
 *
 * @param country the drilled country's label, state the drilled state code, pinPrefix the drilled prefix
 */
public record LocationSection(@JsonUnwrapped Window window, String level, String country, String state,
                              String pinPrefix, List<RegionRow> rows, Unrecognised unrecognised,
                              List<TopProduct> topProducts) implements Section {

    public static final int SMALL = 3;
    public static final String UNKNOWN = "unknown";
    public static final String UNRECOGNISED = "unrecognised";
    public static final String INVALID = "invalid";

    /** @param change percent change in orders against the comparison period */
    public record RegionRow(String key, String label, long orders, double revenue, double averageOrderValue,
                            double share, Long change, boolean small) {
    }

    public record Sample(String raw, long orders) {
    }

    public record Unrecognised(long orders, List<Sample> samples) {
    }

    public record TopProduct(String productId, String name, long units, double revenue) {
    }

    /** The drill-down asked for; validated on construction. */
    public record Filter(String country, String state, String pinPrefix) {

        public Filter {
            country = blank(country);
            state = blank(state) == null ? null : state.trim().toUpperCase(Locale.ROOT);
            pinPrefix = blank(pinPrefix);
            if (state != null && (country == null || !IndianStates.isIndia(country))) {
                throw new IllegalArgumentException("state: choose India as the country first");
            }
            if (state != null && IndianStates.byCode(state).isEmpty()) {
                throw new IllegalArgumentException("state: unknown state code");
            }
            if (pinPrefix != null && state == null) {
                throw new IllegalArgumentException("pinPrefix: choose a state first");
            }
            if (pinPrefix != null && !pinPrefix.matches("\\d{3}")) {
                throw new IllegalArgumentException("pinPrefix: three digits");
            }
        }

        /** A CSV never goes below the 3-digit prefix, so full PIN codes are never exported. */
        public RegionLevel level(boolean csv) {
            if (pinPrefix != null && !csv) {
                return RegionLevel.PIN;
            }
            return state != null ? RegionLevel.PIN_PREFIX : country != null ? RegionLevel.STATE : RegionLevel.COUNTRY;
        }

        private static String blank(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    public static List<RegionRow> rows(List<RegionCount> now, List<RegionCount> before,
                                       Function<String, String> keyOf, Function<String, String> labelOf) {
        Map<String, double[]> current = merge(now, keyOf);
        Map<String, double[]> previous = before == null ? null : merge(before, keyOf);
        double allOrders = current.values().stream().mapToDouble(v -> v[0]).sum();
        return current.entrySet().stream().map(e -> {
            long orders = (long) e.getValue()[0];
            double revenue = e.getValue()[1];
            boolean small = orders < SMALL;
            Long change = small || previous == null ? null
                    : Change.percent(orders, previous.getOrDefault(e.getKey(), new double[2])[0]);
            return new RegionRow(e.getKey(), labelOf.apply(e.getKey()), orders, revenue,
                    orders == 0 ? 0 : Change.round2(revenue / orders), Change.share(orders, allOrders), change, small);
        }).sorted(Comparator.comparingLong(RegionRow::orders).reversed().thenComparing(RegionRow::label)).toList();
    }

    public static Unrecognised unrecognised(List<RegionCount> stateRows) {
        List<RegionCount> unknown = stateRows.stream()
                .filter(r -> IndianStates.normalise(r.key()).isEmpty()).toList();
        Map<String, Long> byRaw = new LinkedHashMap<>();
        unknown.forEach(r -> byRaw.merge(r.key() == null || r.key().isBlank() ? "(blank)" : r.key().trim(),
                r.orders(), Long::sum));
        List<Sample> samples = byRaw.entrySet().stream().map(e -> new Sample(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(Sample::orders).reversed().thenComparing(Sample::raw))
                .limit(10).toList();
        return new Unrecognised(unknown.stream().mapToLong(RegionCount::orders).sum(), samples);
    }

    public static String countryKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return IndianStates.isIndia(raw) ? "india" : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static String countryLabel(String key) {
        if (UNKNOWN.equals(key)) {
            return "Unknown";
        }
        return Arrays.stream(key.split(" ")).filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" "));
    }

    public static String stateKey(String raw) {
        return IndianStates.normalise(raw).map(IndianStates.State::code).orElse(UNRECOGNISED);
    }

    public static String stateLabel(String key) {
        return IndianStates.byCode(key).map(IndianStates.State::name).orElse("Unrecognised");
    }

    public static String pinKey(RegionLevel level, String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return raw.matches(level == RegionLevel.PIN ? "\\d{6}" : "\\d{3}") ? raw : INVALID;
    }

    public static String pinLabel(String key) {
        return UNKNOWN.equals(key) ? "No PIN code" : INVALID.equals(key) ? "Invalid PIN code" : key;
    }

    private static Map<String, double[]> merge(List<RegionCount> rows, Function<String, String> keyOf) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (RegionCount r : rows) {
            double[] t = out.computeIfAbsent(keyOf.apply(r.key()), k -> new double[2]);
            t[0] += r.orders();
            t[1] += r.sales();
        }
        return out;
    }

    @Override
    public List<String> csvHeader() {
        return List.of("level", "key", "label", "orders", "revenue", "averageOrderValue", "share");
    }

    @Override
    public List<List<Object>> csvRows() {
        return rows.stream().map(r -> List.<Object>of(level, r.key(), r.label(), r.orders(), r.revenue(),
                r.averageOrderValue(), r.share())).toList();
    }
}
