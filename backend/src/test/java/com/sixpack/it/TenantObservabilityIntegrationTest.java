package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.tenant.domain.Tenant;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Every request metric, and the login, checkout and audit counters, say which store they belong to. */
class TenantObservabilityIntegrationTest extends TenantIntegrationTest {

    @Autowired
    MeterRegistry meters;

    private long requests(String uri, String tenant) {
        Timer timer = meters.find("http.server.requests").tag("uri", uri).tag("tenant", tenant).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void requestMetricsCarryTheResolvedTenant() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        long before = requests("/api/v1/tenant/config", "acme");

        mvc.perform(get("/api/v1/tenant/config").with(host(hostOf(acme)))).andExpect(status().isOk());

        assertThat(requests("/api/v1/tenant/config", "acme")).isEqualTo(before + 1);
    }

    @Test
    void unresolvedAndTenantlessRequestsAreTaggedToo() throws Exception {
        createTenant("acme", "Acme Shop", "shop.acme.test");
        long unknown = meters.find("http.server.requests").tag("tenant", "unknown").timers().stream()
                .mapToLong(Timer::count).sum();
        long none = requests("/actuator/health", "none");

        mvc.perform(get("/api/v1/tenant/config").with(host("nope.example.org"))).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health").with(host("nope.example.org"))).andExpect(status().isOk());

        assertThat(meters.find("http.server.requests").tag("tenant", "unknown").timers().stream()
                .mapToLong(Timer::count).sum()).isEqualTo(unknown + 1);
        assertThat(requests("/actuator/health", "none")).isEqualTo(none + 1);
    }

    @Test
    void loginAndAuditCountersCarryTheTenant() throws Exception {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        double failures = meters.counter("sixpack.auth.login", "result", "failure", "tenant", "acme").count();
        double audits = meters.counter("sixpack.audit.write", "result", "success", "tenant", "acme").count();

        mvc.perform(post("/api/v1/user/login").with(host(hostOf(acme))).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "nobody@acme.test", "password", "wrong-pass"))))
                .andExpect(status().isUnauthorized());

        assertThat(meters.counter("sixpack.auth.login", "result", "failure", "tenant", "acme").count())
                .isEqualTo(failures + 1);
        assertThat(meters.counter("sixpack.audit.write", "result", "success", "tenant", "acme").count())
                .isEqualTo(audits + 1);
    }
}
