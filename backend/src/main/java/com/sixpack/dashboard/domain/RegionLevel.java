package com.sixpack.dashboard.domain;

/** How far the Location tab has drilled down: country → state → 3-digit PIN prefix → 6-digit PIN. */
public enum RegionLevel {
    COUNTRY, STATE, PIN_PREFIX, PIN
}
