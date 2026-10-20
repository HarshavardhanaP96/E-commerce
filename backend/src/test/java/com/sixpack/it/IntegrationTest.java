package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.storage.TestImages;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;

/** Boots the full app against a real MongoDB (Testcontainers). One container is shared by all test classes. */
@SpringBootTest(properties = {
        "app.security.jwt-secret=test-secret-test-secret-test-secret-0123456789",
        "app.security.auth-requests-per-minute=10000",
        "app.tenant.limits.requests-per-minute=1000000",
        "app.storage.local-dir=target/test-uploads",
        "app.payment.provider=fake",
        "app.user.admin-email=",
        "app.products.variants-enabled=true",
        "app.analytics.summary.startup-delay=-1s" // tests run the daily summary job themselves
})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    static {
        MONGO.start();
    }

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("Ecommerce_test"));
        registry.add("spring.data.mongodb.database", () -> "Ecommerce_test");
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected MongoTemplate mongo;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected PasswordEncoder encoder;

    @Autowired
    protected com.sixpack.tenant.domain.TenantRepository tenantRepository;

    @Autowired
    protected com.sixpack.tenant.application.TenantRegistry tenantRegistry;

    @Autowired
    private com.sixpack.tenant.application.TenantBackfill tenantBackfill;

    private com.sixpack.shared.tenant.TenantContext.Scope defaultTenantScope;

    /** Direct repository calls in tests run as the default tenant, like a request on localhost would. */
    @BeforeEach
    void bindDefaultTenant() {
        var tenant = tenantRepository.findBySlug("default").orElseThrow();
        defaultTenantScope = com.sixpack.shared.tenant.TenantContext.open(
                new com.sixpack.shared.tenant.TenantRef(tenant.id(), tenant.slug()));
    }

    @org.junit.jupiter.api.AfterEach
    void unbindDefaultTenant() {
        defaultTenantScope.close();
    }

    /** What a real migration does to documents inserted by hand without a tenant. */
    protected void backfillLegacy() {
        tenantBackfill.backfill();
    }

    @BeforeEach
    void cleanDatabase() {
        for (String c : new String[] {"users", "products", "orders", "coupons", "payment_intents",
                "password_reset_tokens", "email_verification_tokens", "consents", "categories", "coupon_redemptions", "audit_log"}) {
            mongo.getCollection(c).deleteMany(new org.bson.Document());
        }
    }

    protected String register(String email) throws Exception {
        MvcResult res = mvc.perform(multipart("/api/v1/user/register")
                        .param("name", "Test User").param("email", email).param("password", "password123")
                        .param("gender", "female").param("dob", "2000-05-01")
                        .param("acceptTerms", "true"))
                .andExpect(status().isCreated()).andReturn();
        return body(res).get("token").asText();
    }

    protected String userId(String token) throws Exception {
        MvcResult res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/user/me").header("Authorization", bearer(token))).andReturn();
        return body(res).get("user").get("_id").asText();
    }

    /** Logs the seeded admin in. The admin has NOT accepted the acceptable-use agreement yet. */
    protected String adminTokenWithoutAgreement() throws Exception {
        if (users.findByEmail("admin@test.dev").isEmpty()) {
            users.save(new User(null, "Admin", "admin@test.dev", encoder.encode("admin-password-123"), "",
                    Role.ADMIN, Gender.MALE, LocalDate.of(1990, 1, 1), null));
        }
        MvcResult res = mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "admin@test.dev",
                                "password", "admin-password-123"))))
                .andExpect(status().isOk()).andReturn();
        return body(res).get("token").asText();
    }

    /** Logs the seeded admin in and accepts the admin agreement, so admin endpoints work. */
    protected String adminToken() throws Exception {
        String token = adminTokenWithoutAgreement();
        mvc.perform(post("/api/v1/consent").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("purpose", "admin_aup", "granted", true, "source", "reprompt"))))
                .andExpect(status().isOk());
        return token;
    }

    protected String createProduct(String adminToken, String name, double price, int stock, String category)
            throws Exception {
        MvcResult res = mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png", TestImages.png()))
                        .param("name", name).param("price", String.valueOf(price))
                        .param("stock", String.valueOf(stock)).param("category", category)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isCreated()).andReturn();
        return body(res).get("product").get("_id").asText();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Audit entries with this action, newest first, read straight from the collection. */
    protected List<Document> auditEntries(String action) {
        return mongo.getCollection("audit_log").find(new Document("action", action))
                .sort(new Document("at", -1).append("_id", -1)).into(new ArrayList<>());
    }

    /** The change recorded for one field of an audit entry. */
    protected Document changeOf(Document entry, String field) {
        return entry.getList("changes", Document.class).stream()
                .filter(c -> field.equals(c.getString("field"))).findFirst().orElseThrow();
    }

    /** Checks out one unit of the product as this customer and confirms the fake payment. Returns the order id. */
    protected String placeOrder(String token, String productId) throws Exception {
        String providerOrderId = body(mvc.perform(post("/api/v1/order/checkout")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", providerOrderId,
                                "paymentId", "fake_pay_1", "signature", "fake",
                                "shippingInfo", Map.of("address", "12 MG Road", "city", "Bengaluru",
                                        "state", "Karnataka", "country", "India", "pinCode", "560001")))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
