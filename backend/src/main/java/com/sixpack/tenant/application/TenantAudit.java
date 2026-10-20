package com.sixpack.tenant.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.shared.tenant.Feature;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tenant fields a platform action is audited on. All of them are store configuration rather than personal
 * data (the storefront details are published on the store's legal pages), and the payment entry is only the
 * account id and the name of the environment secret, never the secret itself.
 */
final class TenantAudit {

    static final FieldPolicy POLICY = Changes.policy(fields(), List.of());

    private TenantAudit() {
    }

    private static List<String> fields() {
        List<String> fields = new ArrayList<>(List.of("slug", "name", "status", "domains", "locale", "currency",
                "theme", "legal.termsVersion", "legal.privacyVersion", "legal.adminAupVersion", "payment.provider",
                "payment.keyId", "payment.secretRef", "mail.baseUrl", "storefront.logoUrl", "storefront.legalName",
                "storefront.address", "storefront.email", "storefront.phone", "storefront.grievanceOfficer",
                "storefront.grievanceEmail"));
        for (Feature f : Feature.values()) {
            fields.add("features." + f.key());
        }
        return fields;
    }

    static Map<String, Object> view(Tenant t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("slug", t.slug());
        v.put("name", t.name());
        v.put("status", t.status().name());
        v.put("domains", t.domains());
        v.put("locale", t.locale());
        v.put("currency", t.currency());
        v.put("theme", t.theme().isEmpty() ? null : new java.util.TreeMap<>(t.theme()));
        v.put("legal.termsVersion", t.legal().termsVersion());
        v.put("legal.privacyVersion", t.legal().privacyVersion());
        v.put("legal.adminAupVersion", t.legal().adminAupVersion());
        v.put("payment.provider", t.payment().provider());
        v.put("payment.keyId", t.payment().keyId());
        v.put("payment.secretRef", t.payment().secretRef());
        v.put("mail.baseUrl", t.mail().baseUrl());
        v.put("storefront.logoUrl", t.storefront().logoUrl());
        v.put("storefront.legalName", t.storefront().legalName());
        v.put("storefront.address", t.storefront().address());
        v.put("storefront.email", t.storefront().email());
        v.put("storefront.phone", t.storefront().phone());
        v.put("storefront.grievanceOfficer", t.storefront().grievanceOfficer());
        v.put("storefront.grievanceEmail", t.storefront().grievanceEmail());
        t.features().forEach((key, on) -> v.put("features." + key, on));
        return v;
    }
}
