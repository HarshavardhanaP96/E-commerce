package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.storage.TestImages;
import com.sixpack.tenant.application.TenantPurge;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantStatus;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** Offboarding a store: export its data, schedule deletion, restore within the grace period, purge after it. */
@TestPropertySource(properties = {
        "app.platform.admin-email=root@platform.test",
        "app.platform.admin-password=platform-password-123",
        "app.tenant.deletion-grace=30d"})
class TenantOffboardingIntegrationTest extends TenantIntegrationTest {

    private static final String API = "/api/v1/platform/tenants/";

    @Autowired
    TenantPurge purge;
    @Autowired
    java.util.List<com.sixpack.shared.tenant.BackfillTarget> registered;

    /** Export and purge walk the registered collections, so a new tenant-owned collection must be registered. */
    @Test
    void everyTenantOwnedCollectionIsRegistered() {
        var owned = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.sixpack").stream()
                .filter(c -> c.isAnnotatedWith(org.springframework.data.mongodb.core.mapping.Document.class))
                .filter(c -> c.isAssignableTo(com.sixpack.shared.tenant.TenantOwned.class))
                .map(c -> c.getAnnotationOfType(org.springframework.data.mongodb.core.mapping.Document.class).value())
                .toList();
        assertThat(registered.stream().map(com.sixpack.shared.tenant.BackfillTarget::collection))
                .containsExactlyInAnyOrderElementsOf(owned);
    }

    private String platformToken() throws Exception {
        return body(mvc.perform(post("/api/v1/platform/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "root@platform.test",
                                "password", "platform-password-123"))))
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions platform(String path) throws Exception {
        return mvc.perform(post(API + path).header("Authorization", bearer(platformToken())));
    }

    /** A store with an admin, a customer and a product with an uploaded photo. */
    private Tenant populatedStore() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        registerOn(acme, "buyer@acme.test");
        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .with(host(hostOf(acme)))
                        .param("name", "Acme Anvil").param("price", "100").param("stock", "3")
                        .param("category", "tools").header("Authorization", bearer(adminTokenFor(acme))))
                .andExpect(status().isCreated());
        return acme;
    }

    private Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                files.put(e.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private long ownedBy(Tenant tenant, String collection) {
        return mongo.getCollection(collection).countDocuments(new Document("tenantId", tenant.id()));
    }

    @Test
    void theExportHoldsTheStoresDataAndPhotosButNoSecrets() throws Exception {
        Tenant acme = populatedStore();
        createProduct(adminToken(), "Default Store Mug", 5, 1, "home");

        byte[] zip = mvc.perform(get(API + acme.id() + "/export").header("Authorization", bearer(platformToken())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"acme-export.zip\""))
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, byte[]> files = unzip(zip);

        JsonNode manifest = json.readTree(files.get("manifest.json"));
        assertThat(manifest.get("tenant").get("slug").asText()).isEqualTo("acme");
        assertThat(manifest.get("counts").get("products").asInt()).isEqualTo(1);
        JsonNode products = json.readTree(files.get("products.json"));
        assertThat(products).hasSize(1);
        assertThat(products.get(0).get("name").asText()).isEqualTo("Acme Anvil");
        JsonNode users = json.readTree(files.get("users.json"));
        assertThat(users).hasSize(2);
        assertThat(users.findValues("password")).isEmpty();
        assertThat(files).doesNotContainKeys("password_reset_tokens.json", "payment_intents.json");
        String photo = products.get(0).get("photo").asText();
        assertThat(files).containsKey(photo);
        assertThat(files.get(photo)).isEqualTo(TestImages.png());

        mvc.perform(get("/api/v1/admin/audit").with(host(hostOf(acme))).param("action", "tenant_exported")
                        .header("Authorization", bearer(adminTokenFor(acme))))
                .andExpect(jsonPath("$.entries[0].actorRole").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.entries[0].changes.length()").value(0));
    }

    @Test
    void offboardingClosesTheStoreAndSchedulesDeletion() throws Exception {
        Tenant acme = populatedStore();

        JsonNode tenant = body(platform(acme.id() + "/offboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.status").value("SCHEDULED_FOR_DELETION")).andReturn()).get("tenant");

        Instant due = Instant.parse(tenant.get("deletionDueAt").asText());
        assertThat(due.getNano() % 1_000_000).as("stored to the millisecond, as MongoDB keeps it").isZero();
        assertThat(due).isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(1, ChronoUnit.MINUTES));
        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(acme)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").with(host(hostOf(acme))))
                .andExpect(status().isForbidden());
        assertThat(ownedBy(acme, "audit_log")).isPositive();
        assertThat(mongo.getCollection("audit_log").countDocuments(new Document("tenantId", acme.id())
                .append("action", "TENANT_STATUS_CHANGED"))).isEqualTo(1);
    }

    @Test
    void aScheduledStoreCanBeRestoredWithinTheGracePeriod() throws Exception {
        Tenant acme = populatedStore();
        platform(acme.id() + "/offboard").andExpect(status().isOk());

        platform(acme.id() + "/restore").andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.tenant.deletionDueAt").doesNotExist());

        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(acme)))).andExpect(status().isOk());
        purge.purgeDue(Instant.now().plus(Duration.ofDays(60)));
        assertThat(ownedBy(acme, "products")).isEqualTo(1);
    }

    @Test
    void aScheduledStoreCannotBeActivatedOrSuspendedAndTheDefaultCannotBeOffboarded() throws Exception {
        Tenant acme = populatedStore();
        platform(acme.id() + "/offboard").andExpect(status().isOk());

        platform(acme.id() + "/activate").andExpect(status().isConflict());
        platform(acme.id() + "/suspend").andExpect(status().isConflict());
        platform(acme.id() + "/offboard").andExpect(status().isConflict());
        platform(tenantRepository.findBySlug("default").orElseThrow().id() + "/offboard")
                .andExpect(status().isBadRequest());
        Tenant active = createTenant("globex", "Globex", "shop.globex.test");
        platform(active.id() + "/restore").andExpect(status().isConflict());
    }

    @Test
    void nothingIsPurgedBeforeTheGracePeriodEnds() throws Exception {
        Tenant acme = populatedStore();
        platform(acme.id() + "/offboard").andExpect(status().isOk());

        purge.purgeDue(Instant.now().plus(Duration.ofDays(29)));

        assertThat(ownedBy(acme, "products")).isEqualTo(1);
        assertThat(tenantRepository.findById(acme.id()).orElseThrow().status())
                .isEqualTo(TenantStatus.SCHEDULED_FOR_DELETION);
    }

    @Test
    void afterTheGracePeriodTheStoresDataAndFilesAreDeletedButItsAuditLogIsKept() throws Exception {
        Tenant acme = populatedStore();
        createProduct(adminToken(), "Default Store Mug", 5, 1, "home");
        Path uploads = Path.of("target/test-uploads", acme.id());
        assertThat(uploads).isDirectory();
        platform(acme.id() + "/offboard").andExpect(status().isOk());

        purge.purgeDue(Instant.now().plus(Duration.ofDays(31)));

        for (String collection : new String[] {"users", "products", "categories", "orders", "coupons",
                "consents", "password_reset_tokens", "payment_intents", "coupon_redemptions"}) {
            assertThat(ownedBy(acme, collection)).as(collection).isZero();
        }
        assertThat(Files.exists(uploads)).isFalse();
        assertThat(mongo.getCollection("products").countDocuments(new Document("tenantId",
                tenantRepository.findBySlug("default").orElseThrow().id()))).isEqualTo(1);

        Tenant purged = tenantRepository.findById(acme.id()).orElseThrow();
        assertThat(purged.status()).isEqualTo(TenantStatus.DELETED);
        assertThat(purged.domains()).isEmpty();
        assertThat(mongo.getCollection("audit_log").countDocuments(new Document("tenantId", acme.id())
                .append("action", "TENANT_PURGED"))).isEqualTo(1);
        assertThat(mongo.getCollection("audit_log").find(new Document("tenantId", acme.id())
                .append("action", "TENANT_PURGED")).first().getString("actorRole")).isEqualTo("SYSTEM");
        assertThat(ownedBy(acme, "audit_log")).isGreaterThan(1);
        tenantRegistry.refresh();
        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(acme)))).andExpect(status().isNotFound());
    }

    @Test
    void theAuditTrailShowsTheWholeLifecycle() throws Exception {
        Tenant acme = populatedStore();
        platform(acme.id() + "/offboard").andExpect(status().isOk());
        purge.purgeDue(Instant.now().plus(Duration.ofDays(31)));

        mvc.perform(get("/api/v1/platform/audit").param("tenantId", acme.id()).param("entityType", "tenant")
                        .header("Authorization", bearer(platformToken())))
                .andExpect(jsonPath("$.entries[*].action", hasItem("TENANT_PURGED")))
                .andExpect(jsonPath("$.entries[*].action", hasItem("TENANT_STATUS_CHANGED")));
    }
}
