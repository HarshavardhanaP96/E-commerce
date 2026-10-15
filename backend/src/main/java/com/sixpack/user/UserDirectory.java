package com.sixpack.user;

import java.util.Collection;
import java.util.Map;

/** Public API of the user module for other modules. */
public interface UserDirectory {

    /** @return user id → display name for the ids that exist */
    Map<String, String> namesByIds(Collection<String> ids);
}
