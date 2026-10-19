package com.sixpack.consent.web;

import com.sixpack.consent.ConsentLedger;
import com.sixpack.consent.Purpose;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses every request from an admin who has not accepted the current acceptable-use agreement
 * (compliance-consent.md C12). Runs after authentication, so the role is the verified one. The dialog's own
 * endpoints are exempt, or the admin could never accept.
 */
@Component
class AdminAgreementInterceptor implements HandlerInterceptor {

    static final String MESSAGE = "Please accept the admin acceptable-use agreement to continue";

    private static final List<String> EXEMPT = List.of("/api/v1/user/me", "/api/v1/consent", "/api/v1/legal");

    private final ConsentLedger consents;

    AdminAgreementInterceptor(ConsentLedger consents) {
        this.consents = consents;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return true;
        }
        AuthUser caller = AuthUser.from(token.getToken());
        if (!caller.admin() || exempt(request.getRequestURI())) {
            return true;
        }
        if (!consents.hasAcceptedCurrent(caller.id(), Purpose.ADMIN_AUP)) {
            throw ApiException.forbidden(MESSAGE);
        }
        return true;
    }

    private static boolean exempt(String uri) {
        return EXEMPT.stream().anyMatch(prefix -> uri.equals(prefix) || uri.startsWith(prefix + "/"));
    }
}
