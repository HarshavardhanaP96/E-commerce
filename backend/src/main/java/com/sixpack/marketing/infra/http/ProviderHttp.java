package com.sixpack.marketing.infra.http;

import com.sixpack.marketing.domain.DeliveryCode;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Shared plumbing for the provider adapters: a client per provider host with fixed timeouts, and error mapping. */
public final class ProviderHttp {

    private ProviderHttp() {
    }

    /**
     * A plain builder on purpose: Spring's auto-configured one records the full request URL on trace spans, and the
     * GA4 secret travels in the query string.
     */
    public static RestClient client(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Seconds from a Retry-After header, or null. */
    public static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        try {
            return value == null ? null : Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A provider's own words, safe to show the owner: no secret, no control characters, at most 200 characters. */
    public static String clean(String text, String secret) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = secret == null || secret.isEmpty() ? text : text.replace(secret, "[hidden]");
        s = s.replaceAll("\\p{Cntrl}", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** Status-only mapping; Meta refines its 4xx with error codes. */
    public static DeliveryCode byStatus(int status) {
        if (status == 429) {
            return DeliveryCode.RATE_LIMITED;
        }
        if (status >= 500) {
            return DeliveryCode.PROVIDER_DOWN;
        }
        if (status == 401 || status == 403) {
            return DeliveryCode.AUTH_FAILED;
        }
        return DeliveryCode.REJECTED;
    }
}
