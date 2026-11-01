package com.sixpack.tenant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.audit.AuditAction;
import com.sixpack.shared.tenant.BackfillTarget;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.shared.tenant.TenantRef;
import com.sixpack.storage.FileStorage;
import com.sixpack.tenant.domain.Tenant;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Writes one store's data as a ZIP: {@code manifest.json}, one {@code <collection>.json} array per tenant-owned
 * collection, and the store's uploaded files under their public paths. Password hashes, reset tokens and open
 * checkout sessions are left out; they are secrets or short-lived, not the store's records.
 */
@Service
public class TenantExport {

    static final Set<String> SKIPPED_COLLECTIONS = Set.of("password_reset_tokens", "payment_intents", "marketing_outbox");
    private static final Map<String, Set<String>> SKIPPED_FIELDS = Map.of("users", Set.of("password"),
            "marketing_integrations", Set.of("secrets"));
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private final TenantAdminService admin;
    private final TenantMongo mongo;
    private final FileStorage storage;
    private final List<BackfillTarget> collections;
    private final ObjectMapper mapper;

    TenantExport(TenantAdminService admin, TenantMongo mongo, FileStorage storage, List<BackfillTarget> collections,
                 ObjectMapper mapper) {
        this.admin = admin;
        this.mongo = mongo;
        this.storage = storage;
        this.collections = collections;
        this.mapper = mapper;
    }

    /** @return the tenant exported, for naming the download */
    public Tenant exportTo(String tenantId, OutputStream out) {
        Tenant tenant = admin.get(tenantId);
        TenantContext.runAs(new TenantRef(tenant.id(), tenant.slug()), () -> write(tenant, out));
        admin.record(AuditAction.TENANT_EXPORTED, tenant);
        return tenant;
    }

    private void write(Tenant tenant, OutputStream out) {
        try {
            ZipOutputStream zip = new ZipOutputStream(out);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String collection : collections.stream().map(BackfillTarget::collection).sorted().toList()) {
                if (SKIPPED_COLLECTIONS.contains(collection)) {
                    continue;
                }
                List<Document> docs = mongo.find(new Query(), Document.class, collection);
                Set<String> skipped = SKIPPED_FIELDS.getOrDefault(collection, Set.of());
                zip.putNextEntry(new ZipEntry(collection + ".json"));
                zip.write('[');
                for (int i = 0; i < docs.size(); i++) {
                    Document doc = docs.get(i);
                    skipped.forEach(doc::remove);
                    if (i > 0) {
                        zip.write(',');
                    }
                    zip.write(doc.toJson(JSON).getBytes(StandardCharsets.UTF_8));
                }
                zip.write(']');
                zip.closeEntry();
                counts.put(collection, docs.size());
            }
            List<String> files = storage.tenantFiles();
            for (String path : files) {
                zip.putNextEntry(new ZipEntry(path));
                try (InputStream in = storage.open(path)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("tenant", Map.of("id", tenant.id(), "slug", tenant.slug(), "name", tenant.name(),
                    "domains", tenant.domains()));
            manifest.put("exportedAt", Instant.now().toString());
            manifest.put("counts", counts);
            manifest.put("files", files.size());
            manifest.put("omitted", List.of("users.password", "password_reset_tokens", "payment_intents",
                    "marketing_integrations.secrets", "marketing_outbox"));
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
            zip.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("Export of tenant " + tenant.slug() + " failed", e);
        }
    }
}
