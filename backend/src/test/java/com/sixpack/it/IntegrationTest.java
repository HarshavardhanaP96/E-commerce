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
import java.util.Map;
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
        "app.storage.local-dir=target/test-uploads",
        "app.payment.provider=fake",
        "app.user.admin-email="
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

    @BeforeEach
    void cleanDatabase() {
        for (String c : new String[] {"users", "products", "orders", "coupons", "payment_intents",
                "password_reset_tokens", "consents", "categories", "coupon_redemptions"}) {
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

    protected String adminToken() throws Exception {
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

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
