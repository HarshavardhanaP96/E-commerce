package com.sixpack.user;

import java.util.Optional;

/**
 * Implemented by other modules that must veto deleting an account, e.g. while orders are still being
 * fulfilled. Lets them take part without the user module depending on them.
 */
public interface AccountDeletionGuard {

    /** @return a message safe to show the user if the account can't be deleted yet, otherwise empty */
    Optional<String> blockReason(String userId);
}
