package com.sixpack.audit;

import com.sixpack.shared.ApiException;
import java.util.Locale;

/** Every kind of event the audit trail records. The name is what is stored and what the admin API filters on. */
public enum AuditAction {
    PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED,
    CATEGORY_CREATED, CATEGORY_UPDATED, CATEGORY_DELETED,
    COUPON_CREATED, COUPON_UPDATED, COUPON_DELETED,
    ORDER_PLACED, ORDER_STATUS_CHANGED, ORDER_DELETED,
    USER_REGISTERED, USER_ROLE_CHANGED, USER_STATUS_CHANGED, USER_ERASED, ACCOUNT_DELETED_SELF,
    PROFILE_UPDATED, ADDRESS_ADDED, ADDRESS_UPDATED, ADDRESS_DELETED,
    LOGIN_SUCCEEDED, LOGIN_FAILED, LOGIN_BLOCKED_SUSPENDED,
    PASSWORD_RESET_REQUESTED, PASSWORD_RESET_COMPLETED,
    CONSENT_CHANGED,
    /** Platform administrator actions, recorded in the affected store's own log. */
    TENANT_CREATED, TENANT_UPDATED, TENANT_STATUS_CHANGED,
    TENANT_EXPORTED, TENANT_PURGED;

    public static AuditAction parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("action: unknown action");
        }
    }
}
