package com.sixpack.user.application;

import com.sixpack.shared.tenant.DefaultTenant;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the bootstrap admin from ADMIN_EMAIL/ADMIN_PASSWORD if that account does not exist. Never deletes data. */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final UserProperties props;
    private final DefaultTenant defaultTenant;

    AdminBootstrap(UserRepository users, PasswordEncoder encoder, UserProperties props, DefaultTenant defaultTenant) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.defaultTenant = defaultTenant;
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantContext.runAs(defaultTenant.get(), this::createAdmin);
    }

    private void createAdmin() {
        String email = AuthService.normalizeEmail(props.adminEmail());
        if (email.isEmpty() || props.adminPassword() == null || props.adminPassword().length() < 12) {
            if (!email.isEmpty()) {
                log.warn("ADMIN_PASSWORD missing or shorter than 12 characters; bootstrap admin not created");
            }
            return;
        }
        if (users.existsByEmail(email)) {
            return;
        }
        users.save(new User(null, "Administrator", email, encoder.encode(props.adminPassword()), "", Role.ADMIN,
                Gender.MALE, LocalDate.of(1990, 1, 1), null));
        log.info("Bootstrap admin account created");
    }
}
