package com.sixpack.shared.security;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantProperties;
import com.sixpack.shared.tenant.TenantRef;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * A token is valid only for the tenant it was issued for. Tokens from before tenancy carry no {@code tid} and
 * are honoured for the default tenant only, until they expire.
 */
class TenantClaimValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM = "tid";

    private static final OAuth2Error WRONG_TENANT =
            new OAuth2Error("invalid_token", "The token is not valid for this store", null);

    private final String defaultSlug;

    TenantClaimValidator(TenantProperties props) {
        this.defaultSlug = props.defaultSlug();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        TenantRef tenant = TenantContext.current().orElse(null);
        if (tenant == null) {
            return OAuth2TokenValidatorResult.success(); // paths that run without a tenant
        }
        String tid = jwt.getClaimAsString(CLAIM);
        boolean valid = tid == null ? tenant.slug().equals(defaultSlug) : tid.equals(tenant.id());
        return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(WRONG_TENANT);
    }
}
