package com.sixpack.user.domain;

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

    User save(User user);

    void deleteById(String id);
}
