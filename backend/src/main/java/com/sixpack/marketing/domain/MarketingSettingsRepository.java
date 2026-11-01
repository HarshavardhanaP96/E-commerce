package com.sixpack.marketing.domain;

/** The current store's settings row. Implementations scope every call to the tenant in context and fail without one. */
public interface MarketingSettingsRepository {

    /** The store's settings, or {@link MarketingSettings#NONE} when it has none yet. */
    MarketingSettings find();

    /** Turns the feed on; {@code newToken} is stored only when the store has no token yet. @return what is stored */
    MarketingSettings enableFeed(String newToken);

    /** Turns the feed off and keeps the token, so turning it on again restores the same address. */
    void disableFeed();

    /** Replaces the token of an enabled feed. @return false when the feed is off (nothing changed) */
    boolean rotateFeedToken(String newToken);
}
