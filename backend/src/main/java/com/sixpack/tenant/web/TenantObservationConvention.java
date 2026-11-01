package com.sixpack.tenant.web;

import com.sixpack.shared.tenant.TenantContext;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import java.util.regex.Pattern;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Adds a {@code tenant} key to every HTTP server observation, so both the {@code http.server.requests} metric and
 * the request span name the store. The value is read from a request attribute set by
 * {@link TenantResolutionFilter}, because the observation is stopped after the tenant scope has closed. Every
 * request gets the key (Prometheus needs the same tag keys on every series of a metric).
 */
class TenantObservationConvention extends DefaultServerRequestObservationConvention {

    /** Request attribute holding the tenant slug, {@code unknown} for an unresolved host, or {@code none}. */
    static final String ATTRIBUTE = TenantObservationConvention.class.getName() + ".tenant";
    static final String UNKNOWN = "unknown";
    private static final Pattern FEED_TOKEN = Pattern.compile("^/api/v1/feeds/[^/]+/");

    /** The product feed token is a credential: keep it out of every span's {@code http.url}. */
    @Override
    protected KeyValue httpUrl(ServerRequestObservationContext context) {
        KeyValue url = super.httpUrl(context);
        return KeyValue.of(url.getKey(), FEED_TOKEN.matcher(url.getValue()).replaceFirst("/api/v1/feeds/{token}/"));
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        Object tenant = context.getCarrier().getAttribute(ATTRIBUTE);
        return super.getLowCardinalityKeyValues(context)
                .and(KeyValue.of(TenantContext.MDC_KEY, tenant == null ? TenantContext.NONE : tenant.toString()));
    }
}
