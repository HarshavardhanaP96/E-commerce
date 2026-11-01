package com.sixpack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantRef;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthRateLimitFilterTest {

    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(3, new JsonSecurityHandlers(new ObjectMapper()));

    @Test
    void blocksAfterLimitPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(call("1.1.1.1", "/api/v1/user/login").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse blocked = call("1.1.1.1", "/api/v1/user/login");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("\"success\":false");
        assertThat(call("2.2.2.2", "/api/v1/user/login").getStatus()).isEqualTo(200);
        assertThat(call("1.1.1.1", "/api/v1/product/all").getStatus()).isEqualTo(200);
    }

    @Test
    void limitsAreCountedPerTenant() throws Exception {
        TenantRef acme = new TenantRef("acme-id", "acme");
        TenantRef globex = new TenantRef("globex-id", "globex");
        for (int i = 0; i < 3; i++) {
            assertThat(TenantContext.callAs(acme, () -> callQuietly("9.9.9.9", "/api/v1/user/login").getStatus()))
                    .isEqualTo(200);
        }
        assertThat(TenantContext.callAs(acme, () -> callQuietly("9.9.9.9", "/api/v1/user/login").getStatus()))
                .isEqualTo(429);
        // The same client address on another store has its own budget.
        assertThat(TenantContext.callAs(globex, () -> callQuietly("9.9.9.9", "/api/v1/user/login").getStatus()))
                .isEqualTo(200);
    }

    private MockHttpServletResponse callQuietly(String ip, String uri) {
        try {
            return call(ip, uri);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletResponse call(String ip, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void aBucketCountsEveryMatchingRequestTogether() throws Exception {
        AuthRateLimitFilter feed = new AuthRateLimitFilter(2, new JsonSecurityHandlers(new ObjectMapper()),
                r -> r.getRequestURI().startsWith("/api/v1/feeds/") ? "feed" : null);

        assertThat(call(feed, "GET", "3.3.3.3", "/api/v1/feeds/a/products.xml").getStatus()).isEqualTo(200);
        assertThat(call(feed, "GET", "3.3.3.3", "/api/v1/feeds/b/products.xml").getStatus()).isEqualTo(200);
        // Another token in the path is still the same client: guessing tokens buys no fresh budget.
        assertThat(call(feed, "GET", "3.3.3.3", "/api/v1/feeds/c/products.xml").getStatus()).isEqualTo(429);
        assertThat(call(feed, "GET", "4.4.4.4", "/api/v1/feeds/c/products.xml").getStatus()).isEqualTo(200);
        assertThat(call(feed, "POST", "3.3.3.3", "/api/v1/user/login").getStatus()).isEqualTo(200);
    }

    private static MockHttpServletResponse call(AuthRateLimitFilter f, String method, String ip, String uri)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
