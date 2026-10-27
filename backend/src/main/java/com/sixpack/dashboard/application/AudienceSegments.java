package com.sixpack.dashboard.application;

import com.sixpack.dashboard.domain.AudienceBands.Buyer;
import com.sixpack.dashboard.domain.AudienceSegment;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.Sections.CustomerTotals;
import com.sixpack.dashboard.domain.SectionsReadPort;
import com.sixpack.dashboard.domain.SectionsReadPort.Profile;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The period's buyers as the audience table bins them, and which of them an audience segment keeps. The Customers
 * tab's table and the Sales and Products filters read buyers here, so they always agree.
 */
@Component
class AudienceSegments {

    private static final Profile NONE = new Profile(null, null);

    private final SectionsReadPort read;

    AudienceSegments(SectionsReadPort read) {
        this.read = read;
    }

    /** Each buyer with the gender and birth date on their account; both null once the account is gone. */
    List<Buyer> buyers(List<CustomerTotals> totals) {
        Map<String, Profile> profiles = read.profiles(totals.stream().map(CustomerTotals::userId).toList());
        return totals.stream().map(t -> {
            Profile p = profiles.getOrDefault(t.userId(), NONE);
            return new Buyer(t.userId(), p.gender(), p.dob(), t.orders(), t.revenue());
        }).toList();
    }

    /** Whether {@code segment} may be shown for {@code period} (ages on its last day) and, if so, its buyers. */
    AudienceSegment.Match match(AudienceSegment segment, Period period, ZoneId zone) {
        return segment.match(buyers(read.customerTotals(period.start(zone), period.end(zone))), period.to());
    }
}
