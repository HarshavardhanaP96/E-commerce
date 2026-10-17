package com.sixpack.user.web;

import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.user.application.UserAdminService;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/user")
class UserAdminController {

    record UpdateUserRequest(
            @Pattern(regexp = "(?i)admin|user|", message = "must be admin or user") String role,
            @Pattern(regexp = "(?i)active|suspended|", message = "must be active or suspended") String status) {
    }

    private final UserAdminService service;

    UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping("/all")
    Map<String, Object> all(
            @RequestParam(required = false) @Pattern(regexp = "(?i)admin|user") String role,
            @RequestParam(required = false) @Pattern(regexp = "(?i)male|female") String gender) {
        var users = service.list(role == null ? null : Role.fromWire(role),
                gender == null ? null : Gender.fromWire(gender));
        return ApiResponse.of("users", users.stream().map(UserView::from).toList());
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        return ApiResponse.of("user", UserView.from(service.get(id)));
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateUserRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        boolean hasRole = request.role() != null && !request.role().isBlank();
        boolean hasStatus = request.status() != null && !request.status().isBlank();
        if (!hasRole && !hasStatus) {
            throw ApiException.badRequest("role or status: at least one is required");
        }
        String actingAdminId = AuthUser.from(jwt).id();
        if (hasRole) {
            service.changeRole(id, Role.fromWire(request.role()), actingAdminId);
        }
        if (hasStatus) {
            service.changeStatus(id, UserStatus.parse(request.status()), actingAdminId);
        }
        return ApiResponse.message("User updated successfully");
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, AuthUser.from(jwt).id());
        return ApiResponse.message("User deleted successfully");
    }
}
