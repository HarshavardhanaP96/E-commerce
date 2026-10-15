package com.sixpack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private MockHttpServletResponse call(String ip, String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }
}
