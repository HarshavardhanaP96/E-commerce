package com.sixpack.marketing.infra.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
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

/** Meta Conversions API, Graph API version from configuration (v25.0 when written). */
@Component
class MetaConversionsApi implements MarketingDestination {

    private static final Logger log = LoggerFactory.getLogger(MetaConversionsApi.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Graph API error codes meaning "slow down", "try again" or "bad token". */
    private static final Set<Integer> THROTTLED = Set.of(4, 17, 32, 613, 80004);
    private static final Set<Integer> TRANSIENT = Set.of(1, 2);
    private static final Set<Integer> AUTH = Set.of(102, 190);

    private final RestClient client;
    private final String apiVersion;

    @Autowired
    MetaConversionsApi(MarketingProperties props) {
        this(ProviderHttp.client(props.meta().baseUrl(), props.http().connectTimeout(), props.http().readTimeout()),
                props.meta().apiVersion());
    }

    /** Takes a ready client, so tests can put a mock server behind it. */
    MetaConversionsApi(RestClient client, String apiVersion) {
        this.client = client;
        this.apiVersion = apiVersion;
    }

    @Override
    public Provider provider() {
        return Provider.META;
    }

    @Override
    public void send(Credentials credentials, Conversion conversion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(event(conversion)));
        body.put("access_token", credentials.secret());
        post(credentials, body);
    }

    @Override
    public TestVerdict test(Credentials credentials, String testEventCode, String currency) {
        Conversion sample = new Conversion("test-" + UUID.randomUUID(), Instant.now(), currency, 1.0, 0, 0, null,
                List.of(new Conversion.Item("test-item", "Test item", null, 1.0, 1)),
                new Conversion.Identity(null, null, null, null, null, null, "6Pack test event", null, true));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(event(sample)));
        body.put("test_event_code", testEventCode);
        body.put("access_token", credentials.secret());
        try {
            Map<?, ?> reply = post(credentials, body);
            return reply != null && reply.get("events_received") instanceof Number n && n.intValue() > 0
                    ? new TestVerdict(true, null, "Meta received the test event. Open Events Manager → Test events to see it.")
                    : new TestVerdict(false, DeliveryCode.REJECTED.name(), "Meta answered but did not count the event.");
        } catch (DeliveryException e) {
            return new TestVerdict(false, e.code().name(), e.ownerMessage(Provider.META));
        }
    }

    private Map<?, ?> post(Credentials credentials, Map<String, Object> body) {
        try {
            // The token travels in the JSON body, never in the URL.
            return client.post().uri("/{version}/{pixel}/events", apiVersion, credentials.id())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            throw refused(e, credentials.secret());
        } catch (ResourceAccessException e) {
            throw new DeliveryException(DeliveryCode.TIMEOUT, null, null);
        } catch (RestClientException e) {
            if (ProviderHttp.timedOut(e)) {
                throw new DeliveryException(DeliveryCode.TIMEOUT, null, null);
            }
            log.warn("Meta Conversions API call failed: {}", e.getClass().getSimpleName());
            throw new DeliveryException(DeliveryCode.PROVIDER_DOWN, null, null);
        }
    }

    private static DeliveryException refused(RestClientResponseException e, String secret) {
        Map<?, ?> error = errorOf(e);
        int code = error.get("code") instanceof Number n ? n.intValue() : -1;
        int status = e.getStatusCode().value();
        DeliveryCode kind;
        if (status == 429 || THROTTLED.contains(code)) {
            kind = DeliveryCode.RATE_LIMITED;
        } else if (status >= 500 || TRANSIENT.contains(code) || Boolean.TRUE.equals(error.get("is_transient"))) {
            kind = DeliveryCode.PROVIDER_DOWN;
        } else if (AUTH.contains(code)) {
            kind = DeliveryCode.AUTH_FAILED;
        } else {
            kind = ProviderHttp.byStatus(status);
        }
        String detail = kind == DeliveryCode.REJECTED
                ? ProviderHttp.clean(error.get("message") instanceof String s ? s : null, secret) : null;
        return new DeliveryException(kind, detail, ProviderHttp.retryAfter(e.getResponseHeaders()));
    }

    private static Map<?, ?> errorOf(RestClientResponseException e) {
        try {
            Map<?, ?> body = JSON.readValue(e.getResponseBodyAsByteArray(), Map.class);
            return body != null && body.get("error") instanceof Map<?, ?> m ? m : Map.of();
        } catch (Exception ignored) {
            return Map.of(); // not JSON: map by status only
        }
    }

    static Map<String, Object> event(Conversion c) {
        Conversion.Identity id = c.identity();
        Map<String, Object> user = new LinkedHashMap<>();
        if (id.emailSha256() != null) {
            user.put("em", List.of(id.emailSha256()));
        }
        if (id.phoneSha256() != null) {
            user.put("ph", List.of(id.phoneSha256()));
        }
        if (id.externalIdSha256() != null) {
            user.put("external_id", List.of(id.externalIdSha256()));
        }
        putIf(user, "fbp", id.fbp());
        putIf(user, "fbc", id.fbc());
        putIf(user, "client_user_agent", id.userAgent());
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("currency", c.currency());
        custom.put("value", c.value());
        custom.put("order_id", c.eventId());
        custom.put("content_type", "product");
        custom.put("content_ids", c.items().stream().map(Conversion.Item::id).toList());
        custom.put("contents", c.items().stream().map(i -> Map.of("id", i.id(), "quantity", i.quantity(),
                "item_price", i.price())).toList());
        custom.put("num_items", c.items().stream().mapToInt(Conversion.Item::quantity).sum());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", "Purchase");
        event.put("event_time", c.at().getEpochSecond());
        event.put("event_id", c.eventId()); // = the Pixel's eventID, so Meta counts the purchase once
        event.put("action_source", "website");
        putIf(event, "event_source_url", id.sourceUrl());
        event.put("user_data", user);
        event.put("custom_data", custom);
        return event;
    }

    private static void putIf(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
