package com.sixpack.user.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.user.domain.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the audit log may say about users. Name and phone are personal data, so the log only says they changed.
 * Email, DOB, addresses and the password hash are not in any policy and so are never logged.
 */
final class UserAudit {

    static final FieldPolicy PROFILE = Changes.policy(List.of("locale"), List.of("name", "phone"));
    static final FieldPolicy ROLE = Changes.policy(List.of("role"), List.of());
    static final FieldPolicy STATUS = Changes.policy(List.of("status"), List.of());

    private UserAudit() {
    }

    static Map<String, Object> profile(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", u.name());
        m.put("phone", u.details().phone());
        m.put("locale", u.details().locale());
        return m;
    }
}
