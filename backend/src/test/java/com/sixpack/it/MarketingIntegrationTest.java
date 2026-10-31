package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Helpers for the Admin → Marketing tests: the feature switch, the three admin calls, raw rows. */
abstract class MarketingIntegrationTest extends TenantIntegrationTest {

    protected static final String BASE = "/api/v1/admin/marketing";

    /** The store with the marketing switch set, as a platform administrator would set it. */
    protected Tenant marketing(Tenant t, boolean on) {
        Map<String, Boolean> features = new HashMap<>(t.features());
        features.put("marketing", on);
        Tenant updated = tenantRepository.save(new Tenant(t.id(), t.slug(), t.name(), t.status(), t.domains(),
                t.theme(), t.locale(), t.currency(), t.createdAt(), t.legal(), t.payment(), t.mail(),
                t.storefront(), features, t.deletionDueAt(), t.limits(), t.trialEndsAt(), t.settings()));
        tenantRegistry.refresh();
        return updated;
    }

    protected ResultActions list(Tenant t, String token) throws Exception {
        return mvc.perform(get(BASE).with(host(hostOf(t))).header("Authorization", bearer(token)));
    }

    protected ResultActions save(Tenant t, String token, String provider, Map<String, String> browser)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("browser", browser);
        return mvc.perform(put(BASE + "/" + provider).with(host(hostOf(t))).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions disconnect(Tenant t, String token, String provider) throws Exception {
        return mvc.perform(delete(BASE + "/" + provider).with(host(hostOf(t)))
                .header("Authorization", bearer(token)));
    }

    /** {@code browser("ga4MeasurementId", "G-…", "pixelId", "…")}; values may be blank. */
    protected static Map<String, String> browser(String... fieldsAndValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            m.put(fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return m;
    }

    /** The store's rows in marketing_integrations, read straight from the collection. */
    protected List<Document> rows(Tenant t) {
        return mongo.getCollection("marketing_integrations").find(new Document("tenantId", t.id()))
                .into(new ArrayList<>());
    }

    protected static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
