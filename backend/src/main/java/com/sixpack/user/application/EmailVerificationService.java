package com.sixpack.user.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.shared.ApiException;
import com.sixpack.user.domain.EmailVerificationToken;
import com.sixpack.user.domain.EmailVerificationTokenRepository;
import com.sixpack.user.domain.MailSender;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Proves the customer owns the address they registered with: email a single-use random token, store only its
 * SHA-256 hash. Verification is informational for now; nothing is blocked while an address is unverified.
 */
@Service
public class EmailVerificationService {

    static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final MailSender mail;
    private final UserProperties props;
    private final AuditTrail audit;

    EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens, MailSender mail,
                             UserProperties props, AuditTrail audit) {
        this.users = users;
        this.tokens = tokens;
        this.mail = mail;
        this.props = props;
        this.audit = audit;
    }

    /** Sends the link without ever failing the caller: a mail outage must not stop a registration. */
    void sendQuietly(User user) {
        try {
            send(user);
        } catch (RuntimeException e) {
            log.warn("Could not send the verification email for user {}: {}", user.id(), e.getMessage());
        }
    }

    /** @return false if the address was already verified and nothing was sent */
    public boolean resend(String userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (user.details().emailVerified()) {
            return false;
        }
        send(user);
        return true;
    }

    public void verify(String token) {
        EmailVerificationToken stored = tokens.consumeByHash(PasswordResetService.sha256(token))
                .filter(t -> t.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("Verification link is invalid or has expired"));
        User user = users.findById(stored.userId())
                .orElseThrow(() -> ApiException.badRequest("Verification link is invalid or has expired"));
        users.markEmailVerified(user.id());
        audit.record(AuditEvent.of(AuditAction.EMAIL_VERIFIED).on("user", user.id())
                .by(user.id(), user.role().name()));
    }

    private void send(User user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.deleteByUserId(user.id());
        tokens.save(new EmailVerificationToken(null, user.id(), PasswordResetService.sha256(token),
                Instant.now().plus(TOKEN_TTL)));
        String base = props.frontendUrl() == null ? "" : props.frontendUrl().replaceAll("/+$", "");
        mail.sendEmailVerification(user.email(), user.name(), base + "/verify-email?token=" + token);
    }
}
