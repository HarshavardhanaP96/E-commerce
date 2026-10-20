package com.sixpack.tenant.application;

import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.TokenService;
import com.sixpack.tenant.domain.PlatformAdmin;
import com.sixpack.tenant.domain.PlatformAdminRepository;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PlatformAuthService {

    public static final String ROLE = "PLATFORM_ADMIN";

    public record Login(String token, PlatformAdmin admin) {
    }

    private final PlatformAdminRepository admins;
    private final PasswordEncoder encoder;
    private final TokenService tokens;

    PlatformAuthService(PlatformAdminRepository admins, PasswordEncoder encoder, TokenService tokens) {
        this.admins = admins;
        this.encoder = encoder;
        this.tokens = tokens;
    }

    /** The token has no tenant claim: it is valid only on platform endpoints. */
    public Login login(String email, String password) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        PlatformAdmin admin = admins.findByEmail(normalized).orElse(null);
        if (admin == null || !encoder.matches(password, admin.passwordHash())) {
            throw ApiException.unauthorized("Invalid Email or Password");
        }
        return new Login(tokens.issue(admin.id(), ROLE), admin);
    }
}
