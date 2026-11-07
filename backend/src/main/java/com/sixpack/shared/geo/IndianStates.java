package com.sixpack.shared.geo;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * India's 28 states and 8 union territories, with the spellings shoppers type. Codes are the current ISO 3166-2:IN
 * subdivision codes; older codes (CT, OR, TG, UT, DN, DD) and old names are accepted as aliases. Matching ignores
 * case, spaces and punctuation, and reads "&" as "and".
 */
public final class IndianStates {

    /** {@code aliases}: the other spellings and older codes accepted for it, beyond case, spaces and punctuation. */
    public record State(String code, String name, boolean unionTerritory, List<String> aliases) {
    }

    private static final List<State> ALL;
    private static final Map<String, State> BY_KEY = new HashMap<>();
    private static final Map<String, State> BY_CODE = new HashMap<>();
    private static final Set<String> INDIA = Set.of("india", "in", "ind", "bharat", "republicofindia");

    static {
        state("AP", "Andhra Pradesh");
        state("AR", "Arunachal Pradesh");
        state("AS", "Assam");
        state("BR", "Bihar");
        state("CG", "Chhattisgarh", "CT", "Chattisgarh");
        state("GA", "Goa");
        state("GJ", "Gujarat");
        state("HR", "Haryana");
        state("HP", "Himachal Pradesh");
        state("JH", "Jharkhand");
        state("KA", "Karnataka");
        state("KL", "Kerala");
        state("MP", "Madhya Pradesh");
        state("MH", "Maharashtra");
        state("MN", "Manipur");
        state("ML", "Meghalaya");
        state("MZ", "Mizoram");
        state("NL", "Nagaland");
        state("OD", "Odisha", "OR", "Orissa");
        state("PB", "Punjab");
        state("RJ", "Rajasthan");
        state("SK", "Sikkim");
        state("TN", "Tamil Nadu");
        state("TS", "Telangana", "TG");
        state("TR", "Tripura");
        state("UP", "Uttar Pradesh");
        state("UK", "Uttarakhand", "UT", "Uttaranchal");
        state("WB", "West Bengal");
        territory("AN", "Andaman and Nicobar Islands", "Andaman and Nicobar", "Andaman");
        territory("CH", "Chandigarh");
        territory("DH", "Dadra and Nagar Haveli and Daman and Diu", "DN", "DD", "Dadra and Nagar Haveli",
                "Daman and Diu");
        territory("DL", "Delhi", "New Delhi", "NCT of Delhi", "National Capital Territory of Delhi");
        territory("JK", "Jammu and Kashmir", "J and K", "Kashmir");
        territory("LA", "Ladakh");
        territory("LD", "Lakshadweep");
        territory("PY", "Puducherry", "Pondicherry");
        ALL = BY_CODE.values().stream().sorted(Comparator.comparing(State::name)).toList();
    }

    private IndianStates() {
    }

    public static List<State> all() {
        return ALL;
    }

    public static Optional<State> normalise(String raw) {
        return raw == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(key(raw)));
    }

    public static Optional<State> byCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    public static boolean isIndia(String country) {
        return country != null && INDIA.contains(key(country));
    }

    /**
     * Whether two addresses are in the same state. Both in India: the same Indian state however spelled ("KA",
     * "karnataka "). Otherwise, or when either is not on the list, the trimmed text must match ignoring case: a code
     * like "TN" means something else abroad (Tennessee). Null or blank is never the same.
     */
    public static boolean same(String stateA, String countryA, String stateB, String countryB) {
        if (stateA == null || stateB == null || stateA.isBlank() || stateB.isBlank()) {
            return false;
        }
        if (isIndia(countryA) && isIndia(countryB)) {
            Optional<State> a = normalise(stateA);
            Optional<State> b = normalise(stateB);
            if (a.isPresent() && b.isPresent()) {
                return a.get().equals(b.get());
            }
        }
        return stateA.trim().toLowerCase(Locale.ROOT).equals(stateB.trim().toLowerCase(Locale.ROOT));
    }

    private static void state(String code, String name, String... aliases) {
        add(new State(code, name, false, List.of(aliases)));
    }

    private static void territory(String code, String name, String... aliases) {
        add(new State(code, name, true, List.of(aliases)));
    }

    private static void add(State s) {
        BY_CODE.put(s.code(), s);
        BY_KEY.put(key(s.code()), s);
        BY_KEY.put(key(s.name()), s);
        for (String alias : s.aliases()) {
            BY_KEY.put(key(alias), s);
        }
    }

    private static String key(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^a-z]", "");
    }
}
