package com.sixpack.user.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sixpack.shared.security.AccountAccess;
import com.sixpack.user.domain.User;
import com.sixpack.user.domain.UserRepository;
import com.sixpack.user.domain.UserStatus;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Answers the per-request "may this account still act?" question from a short-lived cache, so a request costs a
 * database read at most once per TTL per account. Changes made on this instance evict at once; another replica
 * may keep serving a suspended account for up to the TTL (ADR-007 trade-off).
 */
@Component
class UserAccountAccess implements AccountAccess {

    static final Duration TTL = Duration.ofSeconds(15);

    private final UserRepository users;
    private final Cache<String, State> states = Caffeine.newBuilder().expireAfterWrite(TTL).maximumSize(10_000).build();

    UserAccountAccess(UserRepository users) {
        this.users = users;
    }

    @Override
    public State stateOf(String userId) {
        return states.get(userId, this::load);
    }

    void evict(String userId) {
        states.invalidate(userId);
    }

    private State load(String userId) {
        return users.findById(userId).map(UserAccountAccess::stateOf).orElse(State.GONE);
    }

    private static State stateOf(User user) {
        return user.details().status() == UserStatus.SUSPENDED ? State.SUSPENDED : State.ACTIVE;
    }
}
