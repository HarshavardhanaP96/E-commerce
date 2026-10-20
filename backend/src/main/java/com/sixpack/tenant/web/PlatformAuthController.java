package com.sixpack.tenant.web;

import com.sixpack.shared.web.ApiResponse;
import com.sixpack.tenant.application.PlatformAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform administrators sign in here. The path runs without a tenant, and the token carries no tenant. */
@RestController
@RequestMapping("/api/v1/platform")
class PlatformAuthController {

    record LoginRequest(@NotBlank @Size(max = 120) String email, @NotBlank @Size(max = 72) String password) {
    }

    private final PlatformAuthService auth;

    PlatformAuthController(PlatformAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        var login = auth.login(request.email(), request.password());
        return ApiResponse.of("message", "Welcome, " + login.admin().name(), "token", login.token());
    }
}
