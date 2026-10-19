package com.sixpack.tenant.application;

import com.sixpack.tenant.domain.PlatformAdmin;
import com.sixpack.tenant.domain.PlatformAdminRepository;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first platform administrator from PLATFORM_ADMIN_EMAIL/PASSWORD. Never edits or deletes one. */
@Component
@Order(5)
class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final PlatformAdminRepository admins;
    private final PasswordEncoder encoder;
    private final PlatformProperties props;

    PlatformAdminBootstrap(PlatformAdminRepository admins, PasswordEncoder encoder, PlatformProperties props) {
        this.admins = admins;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.adminEmail() == null ? "" : props.adminEmail().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            return;
        }
        if (props.adminPassword() == null || props.adminPassword().length() < 12) {
            log.warn("PLATFORM_ADMIN_PASSWORD missing or shorter than 12 characters; platform admin not created");
            return;
        }
        if (!admins.existsByEmail(email)) {
            admins.save(new PlatformAdmin(null, "Platform administrator", email, encoder.encode(props.adminPassword())));
            log.info("Platform administrator account created");
        }
    }
}
