package com.sixpack.dashboard.application;

import static com.sixpack.shared.config.CacheConfig.DASHBOARD;

import com.sixpack.dashboard.domain.LocationReads;
import com.sixpack.dashboard.domain.LocationReads.RegionCount;
import com.sixpack.dashboard.domain.LocationReads.RegionMatch;
import com.sixpack.dashboard.domain.LocationSection;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.RegionLevel;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.dashboard.domain.Window;
import com.sixpack.shared.geo.IndianStates;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * The Location tab. Shoppers typed countries and states freely, so the raw values that mean the region asked for
 * are looked up first (in both periods) and then matched exactly.
 */
@Service
public class LocationService {

    private static final int TOP_PRODUCTS = 5;

    private final LocationReads read;
    private final StoreTime time;

    LocationService(LocationReads read, StoreTime time) {
        this.read = read;
        this.time = time;
    }

    public String cacheTag() {
        return time.zone().getId();
    }

    @Cacheable(cacheNames = DASHBOARD, key = "'location:' + #root.target.cacheTag() + #q + #filter + #csv")
    public LocationSection section(SectionQuery q, LocationSection.Filter filter, boolean csv) {
        ZoneId zone = time.zone();
        RegionLevel level = filter.level(csv);
        Period other = q.comparePeriod();
        List<Period> periods = other == null ? List.of(q.period()) : List.of(q.period(), other);

        RegionMatch match = RegionMatch.ALL;
        LocationSection.Unrecognised unrecognised = null;
        if (level != RegionLevel.COUNTRY) {
            List<String> india = rawValues(q.scope(), periods, zone, RegionMatch.ALL, RegionLevel.COUNTRY,
                    IndianStates::isIndia);
            match = new RegionMatch(india, null, null);
            if (level == RegionLevel.STATE) {
                unrecognised = LocationSection.unrecognised(read.count(q.scope(), q.period().start(zone),
                        q.period().end(zone), match, RegionLevel.STATE));
            } else {
                String code = filter.state();
                List<String> states = rawValues(q.scope(), periods, zone, match, RegionLevel.STATE,
                        raw -> IndianStates.normalise(raw).map(s -> s.code().equals(code)).orElse(false));
                match = new RegionMatch(india, states,
                        level == RegionLevel.PIN ? Integer.valueOf(filter.pinPrefix()) : null);
            }
        }

        List<RegionCount> now = read.count(q.scope(), q.period().start(zone), q.period().end(zone), match, level);
        List<RegionCount> before = other == null ? null
                : read.count(q.scope(), other.start(zone), other.end(zone), match, level);
        Function<String, String> keyOf = switch (level) {
            case COUNTRY -> LocationSection::countryKey;
            case STATE -> LocationSection::stateKey;
            case PIN_PREFIX, PIN -> raw -> LocationSection.pinKey(level, raw);
        };
        Function<String, String> labelOf = switch (level) {
            case COUNTRY -> LocationSection::countryLabel;
            case STATE -> LocationSection::stateLabel;
            case PIN_PREFIX, PIN -> LocationSection::pinLabel;
        };
        List<LocationSection.TopProduct> top = level == RegionLevel.COUNTRY ? List.of()
                : read.topProducts(q.scope(), q.period().start(zone), q.period().end(zone), match, TOP_PRODUCTS)
                        .stream().map(s -> new LocationSection.TopProduct(s.productId(), s.name(), s.units(),
                                s.revenue())).toList();
        String country = filter.country() == null ? null
                : LocationSection.countryLabel(LocationSection.countryKey(filter.country()));
        return new LocationSection(Window.of(q, zone), level.name().toLowerCase(Locale.ROOT), country,
                filter.state(), level == RegionLevel.PIN ? filter.pinPrefix() : null,
                LocationSection.rows(now, before, keyOf, labelOf), unrecognised, top);
    }

    /** The stored spellings, across all the periods shown, that the test accepts. Empty matches nothing. */
    private List<String> rawValues(Scope scope, List<Period> periods, ZoneId zone, RegionMatch match,
                                   RegionLevel level, Predicate<String> accept) {
        Set<String> out = new LinkedHashSet<>();
        for (Period p : periods) {
            Instant from = p.start(zone);
            Instant to = p.end(zone);
            for (RegionCount r : read.count(scope, from, to, match, level)) {
                if (r.key() != null && accept.test(r.key())) {
                    out.add(r.key());
                }
            }
        }
        return new ArrayList<>(out);
    }
}
