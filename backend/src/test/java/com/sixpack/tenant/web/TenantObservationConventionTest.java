package com.sixpack.tenant.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantObservationConventionTest {

    private final TenantObservationConvention convention = new TenantObservationConvention();

    private String httpUrl(String uri) {
        var context = new ServerRequestObservationContext(new MockHttpServletRequest("GET", uri),
                new MockHttpServletResponse());
        for (KeyValue kv : convention.getHighCardinalityKeyValues(context)) {
            if (kv.getKey().equals("http.url")) {
                return kv.getValue();
            }
        }
        throw new AssertionError("no http.url");
    }

    @Test
    void aFeedTokenNeverReachesATrace() {
        assertThat(httpUrl("/api/v1/feeds/Zm9vYmFy_-1234567890/products.xml"))
                .isEqualTo("/api/v1/feeds/{token}/products.xml");
    }

    @Test
    void otherAddressesAreKept() {
        assertThat(httpUrl("/api/v1/admin/marketing/feed")).isEqualTo("/api/v1/admin/marketing/feed");
    }
}
