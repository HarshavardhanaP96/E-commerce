package com.sixpack.dashboard.domain;

/** What a period is compared with. */
public enum Compare {
    PREVIOUS, YEAR, NONE;

    /** @return the period to compare with, or null for {@link #NONE} */
    public Period apply(Period period) {
        return switch (this) {
            case PREVIOUS -> period.previous();
            case YEAR -> period.yearBefore();
            case NONE -> null;
        };
    }
}
