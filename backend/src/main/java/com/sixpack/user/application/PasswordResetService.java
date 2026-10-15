package com.sixpack.user.application;

import com.sixpack.shared.ApiException;
import com.sixpack.user.domain.MailSender;
import com.sixpack.user.domain.PasswordResetToken;
import com.sixpack.user.domain.PasswordResetTokenRepository;
import com.sixpack.user.domain.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
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

    PasswordResetService(UserRepository users, PasswordResetTokenRepository tokens, MailSender mail,
                         PasswordEncoder encoder, UserProperties props) {
        this.users = users;
        this.tokens = tokens;
        this.mail = mail;
        this.encoder = encoder;
        this.props = props;
    }

    /** Always succeeds from the caller's view so the endpoint cannot be used to discover accounts. */
    public void requestReset(String email) {
        users.findByEmail(AuthService.normalizeEmail(email)).ifPresent(user -> {
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            tokens.deleteByUserId(user.id());
            tokens.save(new PasswordResetToken(null, user.id(), sha256(token), Instant.now().plus(TOKEN_TTL)));
            String base = props.frontendUrl() == null ? "" : props.frontendUrl().replaceAll("/+$", "");
            mail.sendPasswordReset(user.email(), user.name(), base + "/forgot-password?token=" + token);
        });
    }

    public void reset(String token, String newPassword) {
        PasswordResetToken stored = tokens.consumeByHash(sha256(token))
                .filter(t -> t.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("Reset link is invalid or has expired"));
        var user = users.findById(stored.userId())
                .orElseThrow(() -> ApiException.badRequest("Reset link is invalid or has expired"));
        users.save(user.withPasswordHash(encoder.encode(newPassword)));
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
