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
}
