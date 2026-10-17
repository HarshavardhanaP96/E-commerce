package com.sixpack.user;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Public API of the user module for other modules. */
public interface UserDirectory {

    /** @return user id → display name for the ids that exist */
    Map<String, String> namesByIds(Collection<String> ids);

    /** @return the user's contact details, or empty if the account no longer exists */
    Optional<UserContact> contactById(String id);
}
