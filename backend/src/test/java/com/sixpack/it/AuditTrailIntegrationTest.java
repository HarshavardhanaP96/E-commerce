package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import com.sixpack.audit.domain.AuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditTrailIntegrationTest extends IntegrationTest {

    @Autowired
    AuditTrail audit;
    @Autowired
    MeterRegistry meters;
    @MockitoSpyBean
    AuditRepository repository;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static AuditEvent sample() {
        return AuditEvent.of(AuditAction.PRODUCT_CREATED).on("product", "p1")
                .changing(List.of(FieldChange.of("price", null, 10.0)));
    }

    private Document only() {
        List<Document> all = mongo.getCollection("audit_log").find().into(new java.util.ArrayList<>());
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    void anEventOutsideARequestIsRecordedAsTheSystem() {
        // Spring's test framework binds a mock request to the thread; drop it to simulate "no request at all".
        RequestContextHolder.resetRequestAttributes();
        audit.record(sample());

        Document entry = only();
        assertThat(entry.getString("actorRole")).isEqualTo("SYSTEM");
        assertThat(entry.getString("actorId")).isNull();
        assertThat(entry.getString("action")).isEqualTo("PRODUCT_CREATED");
        assertThat(entry.getString("entityType")).isEqualTo("product");
        assertThat(entry.getString("entityId")).isEqualTo("p1");
        assertThat(entry.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(entry.getString("ipHash")).isEmpty();
        assertThat(changeOf(entry, "price").get("after")).isEqualTo(10.0);
        assertThat(entry.get("at")).isNotNull();
    }

    @Test
    void theActorAndRequestContextComeFromTheRequestNotTheCaller() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("User-Agent", "JUnit-Audit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("admin-1").claim("role", "ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        audit.record(sample());

        Document entry = only();
        assertThat(entry.getString("actorId")).isEqualTo("admin-1");
        assertThat(entry.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(entry.getString("ipHash")).hasSize(64).doesNotContain("203.0.113.9");
        assertThat(entry.getString("userAgent")).isEqualTo("JUnit-Audit");
    }

    @Test
    void anUnauthenticatedRequestIsAnonymousAndAnAttemptedEmailIsOnlyStoredHashed() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED).failed().aboutSubject("Victim@Example.com "));
        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED).failed().aboutSubject("victim@example.com"));

        List<Document> entries = mongo.getCollection("audit_log").find().into(new java.util.ArrayList<>());
        assertThat(entries).hasSize(2).allSatisfy(e -> {
            assertThat(e.getString("actorRole")).isEqualTo("ANONYMOUS");
            assertThat(e.getString("outcome")).isEqualTo("FAILURE");
            assertThat(e.getString("subjectHash")).hasSize(64);
            assertThat(e.toJson().toLowerCase()).doesNotContain("victim");
        });
        assertThat(entries.get(0).getString("subjectHash")).isEqualTo(entries.get(1).getString("subjectHash"));
    }

    @Test
    void anExplicitActorOverridesTheSecurityContext() {
        audit.record(AuditEvent.of(AuditAction.USER_REGISTERED).on("user", "u9").by("u9", "USER"));

        assertThat(only().getString("actorId")).isEqualTo("u9");
        assertThat(only().getString("actorRole")).isEqualTo("USER");
    }

    @Test
    void aFailingWriteNeverReachesTheCallerAndIsCounted() {
        double before = meters.counter("sixpack.audit.write", "result", "failure", "tenant", "default").count();
        doThrow(new RuntimeException("db down")).when(repository).append(any());

        assertThatCode(() -> audit.record(sample())).doesNotThrowAnyException();

        assertThat(meters.counter("sixpack.audit.write", "result", "failure", "tenant", "default").count()).isEqualTo(before + 1);
    }

    @Test
    void entriesExpireAfterTheRetentionPeriod() {
        audit.record(sample());

        IndexInfo ttl = mongo.indexOps("audit_log").getIndexInfo().stream()
                .filter(i -> i.getIndexFields().stream().anyMatch(f -> "at".equals(f.getKey())))
                .filter(i -> i.getExpireAfter().isPresent()).findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).contains(Duration.ofDays(365));
    }

    @Test
    void theRepositoryCanOnlyAppendAndSearch() {
        assertThat(Arrays.stream(AuditRepository.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("append", "search", "searchAllTenants");
    }
}
