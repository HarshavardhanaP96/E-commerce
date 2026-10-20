package com.sixpack.shared.security;

import com.sixpack.shared.tenant.TenantContext;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues access tokens. Role values are upper-case ({@code ADMIN}/{@code USER}) and become {@code ROLE_*} authorities. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final SecurityProperties props;

    TokenService(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String issue(String userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(props.jwtTtl()))
                .claim(AuthUser.ROLE_CLAIM, role);
        TenantContext.current().ifPresent(t -> claims.claim(TenantClaimValidator.CLAIM, t.id()));
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
