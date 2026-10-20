package com.sixpack.user;

/** Public API of the user module for onboarding: create the first administrator of the current tenant. */
public interface AdminProvisioning {

    /**
     * Creates an administrator account in the tenant bound to {@code TenantContext}.
     *
     * @throws com.sixpack.shared.ApiException 409 if the email is already registered in that tenant
     */
    void createAdmin(String name, String email, String password);
}
