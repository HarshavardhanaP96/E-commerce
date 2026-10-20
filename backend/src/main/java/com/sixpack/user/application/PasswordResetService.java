package com.sixpack.user.application;

import com.sixpack.shared.tenant.TenantSettings;
import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.shared.ApiException;
import com.sixpack.user.domain.MailSender;
import com.sixpack.user.domain.PasswordResetToken;
import com.sixpack.user.domain.PasswordResetTokenRepository;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Two-step reset: email a single-use random token; only its SHA-256 hash is stored. */
@Service
public class PasswordResetService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final MailSender mail;
    private final PasswordEncoder encoder;
    private final UserProperties props;
    private final TenantSettings tenant;
    private final AuditTrail audit;

    PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens, MailSender mail,
                         PasswordEncoder encoder, UserProperties props, AuditTrail audit, TenantSettings tenant) {
        this.users = users;
        this.tokens = tokens;
        this.mail = mail;
        this.encoder = encoder;
        this.props = props;
        this.tenant = tenant;
        this.audit = audit;
    }

    /** Always succeeds from the caller's view so the endpoint cannot be used to discover accounts. */
    public void requestReset(String email) {
        String normalized = AuthService.normalizeEmail(email);
        Optional<User> found = users.findByEmail(normalized);
        found.ifPresent(this::sendResetLink);
        AuditEvent event = AuditEvent.of(AuditAction.PASSWORD_RESET_REQUESTED).aboutSubject(normalized);
        audit.record(found.map(u -> event.on("user", u.id())).orElse(event));
    }

    private void sendResetLink(User user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.deleteByUserId(user.id());
        tokens.save(new PasswordResetToken(null, user.id(), sha256(token), Instant.now().plus(TOKEN_TTL)));
        String base = storefrontUrl().replaceAll("/+$", "");
        mail.sendPasswordReset(user.email(), user.name(), base + "/forgot-password?token=" + token);
    }

    /** The tenant's own storefront; the default tenant keeps using {@code app.user.frontend-url}. */
    private String storefrontUrl() {
        if (tenant.mail().baseUrl() != null) {
            return tenant.mail().baseUrl();
        }
        if (!tenant.isDefaultTenant() && tenant.primaryDomain() != null) {
            return "https://" + tenant.primaryDomain();
        }
        return props.frontendUrl() == null ? "" : props.frontendUrl();
    }

    public void reset(String token, String newPassword) {
        PasswordResetToken stored = tokens.consumeByHash(sha256(token))
                .filter(t -> t.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("Reset link is invalid or has expired"));
        var user = users.findById(stored.userId())
                .orElseThrow(() -> ApiException.badRequest("Reset link is invalid or has expired"));
        users.save(user.withPasswordHash(encoder.encode(newPassword)));
        audit.record(AuditEvent.of(AuditAction.PASSWORD_RESET_COMPLETED).on("user", user.id())
                .by(user.id(), user.role().name()));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
