package com.sixpack.shared.security;

/**
 * Asked on every authenticated request whether the account behind a valid token may still act. Tokens live for
 * hours, so suspending or deleting an account has to be checked here and not only at login.
 */
public interface AccountAccess {

    enum State { ACTIVE, SUSPENDED, GONE }

    State stateOf(String userId);
}
