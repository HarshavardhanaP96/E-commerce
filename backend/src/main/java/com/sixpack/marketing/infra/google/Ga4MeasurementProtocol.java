package com.sixpack.marketing.infra.google;

import com.sixpack.marketing.application.MarketingProperties;
import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.DeliveryCode;
import com.sixpack.marketing.domain.DeliveryException;
import com.sixpack.marketing.domain.MarketingDestination;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.infra.http.ProviderHttp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** GA4 Measurement Protocol. The API secret is a query parameter (Google's design), so nothing here logs a URL. */
@Component
class Ga4MeasurementProtocol implements MarketingDestination {

    private static final Logger log = LoggerFactory.getLogger(Ga4MeasurementProtocol.class);

    private final RestClient client;

    @Autowired
    Ga4MeasurementProtocol(MarketingProperties props) {
        this(ProviderHttp.client(props.google().baseUrl(), props.http().connectTimeout(), props.http().readTimeout()));
    }

    /** Takes a ready client, so tests can put a mock server behind it. */
    Ga4MeasurementProtocol(RestClient client) {
        this.client = client;
    }

    @Override
    public Provider provider() {
        return Provider.GOOGLE;
    }

    @Override
    public void send(Credentials credentials, Conversion conversion) {
        call("/mp/collect", credentials, payload(conversion, clientId(conversion)), Void.class);
    }

    @Override
    public TestVerdict test(Credentials credentials, String testEventCode, String currency) {
        Conversion sample = new Conversion("test-" + UUID.randomUUID(), Instant.now(), currency, 1.0, 0, 0, null,
                List.of(new Conversion.Item("test-item", "Test item", null, 1.0, 1)),
                new Conversion.Identity(null, null, null, null, null, null, null, null, false));
        try {
            Map<?, ?> reply = call("/debug/mp/collect", credentials, payload(sample, "test.1"), Map.class);
            List<?> problems = reply != null && reply.get("validationMessages") instanceof List<?> l ? l : List.of();
            if (problems.isEmpty()) {
                return new TestVerdict(true, null, "Google accepted the event. Google does not check the API secret "
                        + "here, so look for your next order in GA4 → Realtime.");
            }
            String first = problems.get(0) instanceof Map<?, ?> m && m.get("description") instanceof String s ? s
                    : "the event was not valid";
            return new TestVerdict(false, DeliveryCode.REJECTED.name(),
                    "Google found a problem: " + ProviderHttp.clean(first, credentials.secret()));
        } catch (DeliveryException e) {
            return new TestVerdict(false, e.code().name(), e.ownerMessage(Provider.GOOGLE));
        }
    }

    private <T> T call(String path, Credentials credentials, Map<String, Object> body, Class<T> type) {
        try {
            return client.post().uri(path + "?measurement_id={id}&api_secret={secret}", credentials.id(),
                    credentials.secret()).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            DeliveryCode kind = ProviderHttp.byStatus(status);
            throw new DeliveryException(kind, kind == DeliveryCode.REJECTED ? "HTTP " + status : null,
                    ProviderHttp.retryAfter(e.getResponseHeaders()));
        } catch (ResourceAccessException e) {
            // Never log e: its message holds the request URL, and with it the API secret.
            throw new DeliveryException(DeliveryCode.TIMEOUT, null, null);
        } catch (RestClientException e) {
            log.warn("GA4 Measurement Protocol call failed: {}", e.getClass().getSimpleName());
            throw new DeliveryException(DeliveryCode.PROVIDER_DOWN, null, null);
        }
    }

    static Map<String, Object> payload(Conversion c, String clientId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("transaction_id", c.eventId()); // = the browser's transaction_id, so GA4 keeps one purchase
        params.put("currency", c.currency());
        params.put("value", c.value());
        params.put("tax", c.tax());
        params.put("shipping", c.shipping());
        if (c.coupon() != null) {
            params.put("coupon", c.coupon());
        }
        params.put("items", c.items().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_id", i.id());
            item.put("item_name", i.name());
            if (i.variant() != null) {
                item.put("item_variant", i.variant());
            }
            item.put("price", i.price());
            item.put("quantity", i.quantity());
            return item;
        }).toList());
        String ads = c.identity().adConsent() ? "GRANTED" : "DENIED";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("timestamp_micros", c.at().toEpochMilli() * 1000);
        body.put("consent", Map.of("ad_user_data", ads, "ad_personalization", ads));
        body.put("events", List.of(Map.of("name", "purchase", "params", params)));
        return body;
    }

    /** The browser's GA client id when the shopper allowed analytics cookies, else a stable one made from the order. */
    static String clientId(Conversion c) {
        return c.identity().gaClientId() != null ? c.identity().gaClientId()
                : Integer.toUnsignedString(c.eventId().hashCode()) + "." + c.at().getEpochSecond();
    }
}
