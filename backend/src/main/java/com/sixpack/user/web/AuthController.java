package com.sixpack.user.web;

import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.user.application.AuthService;
import com.sixpack.user.application.AuthService.AuthResult;
import com.sixpack.user.application.AuthService.RegisterCommand;
import com.sixpack.user.application.PasswordResetService;
import com.sixpack.user.domain.Gender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/user")
class AuthController {

    record RegisterForm(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Email @Size(max = 120) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Pattern(regexp = "(?i)male|female", message = "must be male or female") String gender,
            @NotNull @Past @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob) {
    }

    record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {
    }

    record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    record ResetPasswordRequest(@NotBlank @Size(max = 128) String token,
                                @NotBlank @Size(min = 8, max = 72) String password) {
    }

    private final AuthService auth;
    private final PasswordResetService passwordReset;

    AuthController(AuthService auth, PasswordResetService passwordReset) {
        this.auth = auth;
        this.passwordReset = passwordReset;
    }

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@Valid @ModelAttribute RegisterForm form,
                                 @RequestPart(value = "photo", required = false) MultipartFile photo) {
        AuthResult result = auth.register(new RegisterCommand(form.name(), form.email(), form.password(),
                Gender.fromWire(form.gender()), form.dob(), bytes(photo)));
        return ApiResponse.of("message", "Welcome, " + result.user().name(),
                "token", result.token(), "user", UserView.from(result.user()));
    }

    @PostMapping("/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = auth.login(request.email(), request.password());
        return ApiResponse.of("message", "Welcome back, " + result.user().name(),
                "token", result.token(), "user", UserView.from(result.user()));
    }

    @PostMapping("/forgot-password")
    Map<String, Object> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordReset.requestReset(request.email());
        return ApiResponse.message("If an account exists for that email, a reset link has been sent");
    }

    @PostMapping("/reset-password")
    Map<String, Object> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordReset.reset(request.token(), request.password());
        return ApiResponse.message("Password updated successfully");
    }

    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of("user", UserView.from(auth.me(AuthUser.from(jwt).id())));
    }

    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("photo: could not be read");
        }
    }
}
