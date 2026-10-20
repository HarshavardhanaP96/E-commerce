package com.sixpack.tenant.domain;

import java.util.Optional;

public interface PlatformAdminRepository {
    Optional<PlatformAdmin> findByEmail(String email);

    boolean existsByEmail(String email);

    PlatformAdmin save(PlatformAdmin admin);
}
