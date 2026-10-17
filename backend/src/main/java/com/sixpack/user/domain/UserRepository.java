package com.sixpack.user.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence port for users. */
public interface UserRepository {

    Optional<User> findById(String id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findAll(Role role, Gender gender);

    List<User> findAllById(Collection<String> ids);

    /** Sets only the non-null arguments. */
    void updateProfile(String id, String name, String phone, String locale);

    void replaceAddresses(String id, List<Address> addresses);

    void touchLastLogin(String id, Instant at);

    void setStatus(String id, UserStatus status);

    User save(User user);

    void deleteById(String id);
}
