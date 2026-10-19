package com.sixpack.user.application;

import com.sixpack.shared.ApiException;
import com.sixpack.user.AdminProvisioning;
import com.sixpack.user.domain.Gender;
import com.sixpack.user.domain.Role;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import java.time.LocalDate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class AdminProvisioningService implements AdminProvisioning {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    AdminProvisioningService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void createAdmin(String name, String email, String password) {
        String normalized = AuthService.normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw ApiException.conflict("User already exists");
        }
        users.save(new User(null, name.trim(), normalized, encoder.encode(password), "", Role.ADMIN, Gender.MALE,
                LocalDate.of(1990, 1, 1), null));
    }
}
