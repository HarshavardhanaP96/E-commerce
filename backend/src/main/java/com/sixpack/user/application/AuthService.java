package com.sixpack.user.application;

import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.TokenService;
import com.sixpack.storage.FileStorage;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public record RegisterCommand(String name, String email, String password, Gender gender, LocalDate dob,
                                  byte[] photo) {
    }

    public record AuthResult(String token, User user) {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final FileStorage storage;
    private final MeterRegistry meters;

    AuthService(UserRepository users, PasswordEncoder encoder, TokenService tokens, FileStorage storage,
                MeterRegistry meters) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.storage = storage;
        this.meters = meters;
    }

    public AuthResult register(RegisterCommand cmd) {
        String email = normalizeEmail(cmd.email());
        if (cmd.dob().isAfter(LocalDate.now())) {
            throw ApiException.badRequest("dob: must be in the past");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("User already exists");
        }
        String photo = cmd.photo() != null && cmd.photo().length > 0 ? storage.storeImage(cmd.photo()) : "";
        User saved = users.save(User.newCustomer(cmd.name().trim(), email, encoder.encode(cmd.password()), photo,
                cmd.gender(), cmd.dob()));
        return new AuthResult(issue(saved), saved);
    }

    public AuthResult login(String email, String password) {
        User user = users.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null || user.passwordHash() == null || !encoder.matches(password, user.passwordHash())) {
            meters.counter("sixpack.auth.login", "result", "failure").increment();
            throw ApiException.unauthorized("Invalid Email or Password");
        }
        meters.counter("sixpack.auth.login", "result", "success").increment();
        return new AuthResult(issue(user), user);
    }

    public User me(String userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
    }

    private String issue(User user) {
        return tokens.issue(user.id(), user.role().name());
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
