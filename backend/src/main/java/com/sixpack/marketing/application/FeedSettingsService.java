package com.sixpack.marketing.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.audit.FieldChange;
import com.sixpack.marketing.domain.MarketingSettings;
import com.sixpack.marketing.domain.MarketingSettingsRepository;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.tenant.TenantSettings;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The store's product feed switch and secret address. Callers check {@code Feature.MARKETING} first. */
@Service
public class FeedSettingsService {

    static final String ENTITY = "marketing_settings";
    static final String ENTITY_ID = "feed";
    /** {@code enabled} is logged with its values; the token only as "changed". */
    static final FieldPolicy POLICY = Changes.policy(List.of("enabled"), List.of("feedToken"));
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the admin sees: whether the feed is on and, while it is, its address. */
    public record Feed(boolean enabled, String url) {
    }

    private final MarketingSettingsRepository settings;
    private final TenantSettings tenant;
    private final AuditTrail audit;

    FeedSettingsService(MarketingSettingsRepository settings, TenantSettings tenant, AuditTrail audit) {
        this.settings = settings;
        this.tenant = tenant;
        this.audit = audit;
    }

    public Feed view() {
        return view(settings.find());
    }

    /** Turns the feed on (creating its token the first time) or off (keeping the token); audited when it changes. */
    public Feed setEnabled(boolean enabled) {
        MarketingSettings before = settings.find();
        MarketingSettings after;
        if (enabled) {
            after = settings.enableFeed(newToken());
        } else {
            settings.disableFeed();
            after = new MarketingSettings(false, before.feedToken());
        }
        if (before.feedEnabled() != after.feedEnabled()) {
            audit.record(AuditEvent.of(AuditAction.FEED_UPDATED).on(ENTITY, ENTITY_ID)
                    .changing(Changes.diff(audited(before), audited(after), POLICY)));
        }
        return view(after);
    }

    /** Gives the feed a new address; the old one stops working at once. */
    public Feed rotate() {
        String token = newToken();
        if (!settings.rotateFeedToken(token)) {
            throw ApiException.badRequest("feed: enable it first");
        }
        audit.record(AuditEvent.of(AuditAction.FEED_TOKEN_ROTATED).on(ENTITY, ENTITY_ID)
                .changing(List.of(FieldChange.redacted("feedToken"))));
        return view(new MarketingSettings(true, token));
    }

    /** Whether {@code token} opens the current store's feed: on, with a domain to link to, and the right token. */
    public boolean opens(String token) {
        return tenant.primaryDomain() != null && settings.find().opensFeed(token);
    }

    private Feed view(MarketingSettings s) {
        String domain = tenant.primaryDomain();
        return new Feed(s.feedEnabled(), s.feedEnabled() && domain != null
                ? "https://" + domain + "/api/v1/feeds/" + s.feedToken() + "/products.xml" : null);
    }

    private static Map<String, Object> audited(MarketingSettings s) {
        Map<String, Object> m = new HashMap<>(); // not Map.of: the token may be null
        m.put("enabled", s.feedEnabled());
        m.put("feedToken", s.feedToken());
        return m;
    }

    /** 32 random bytes, URL-safe base64 without padding: 43 characters. */
    private static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
