package com.sixpack.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Writes security failures in the standard `{success:false, message}` envelope. */
@Component
class JsonSecurityHandlers {

    private final ObjectMapper mapper;

    JsonSecurityHandlers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> write(response, HttpServletResponse.SC_UNAUTHORIZED,
                "Please login to access this resource");
    }

    AccessDeniedHandler accessDenied() {
        return (request, response, ex) -> write(response, HttpServletResponse.SC_FORBIDDEN, "You are not authorised");
    }

    void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(message));
    }
}
