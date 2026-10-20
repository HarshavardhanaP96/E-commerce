package com.sixpack.dashboard.application;

import com.sixpack.shared.tenant.StoreConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The store's own time zone (else the platform default) and today's date in it. */
@Component
public class StoreTime {

    private final StoreConfig store;
    private final Clock clock;

    @Autowired
    public StoreTime(StoreConfig store) {
        this(store, Clock.systemUTC());
    }

    StoreTime(StoreConfig store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ZoneId zone() {
        return ZoneId.of(store.effective().timeZone());
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }
}
