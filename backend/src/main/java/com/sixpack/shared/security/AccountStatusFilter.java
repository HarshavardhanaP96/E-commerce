package com.sixpack.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects a verified token whose account has been suspended or deleted since the token was issued. */
class AccountStatusFilter extends OncePerRequestFilter {

    private final AccountAccess accounts;
    private final JsonSecurityHandlers handlers;

    AccountStatusFilter(AccountAccess accounts, JsonSecurityHandlers handlers) {
        this.accounts = accounts;
        this.handlers = handlers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            switch (accounts.stateOf(jwt.getToken().getSubject())) {
                case SUSPENDED -> {
                    SecurityContextHolder.clearContext();
                    handlers.write(response, HttpServletResponse.SC_FORBIDDEN, "Your account is suspended");
                    return;
                }
                case GONE -> {
                    SecurityContextHolder.clearContext();
                    handlers.write(response, HttpServletResponse.SC_UNAUTHORIZED, "Account no longer exists");
                    return;
                }
                case ACTIVE -> { }
            }
        }
        chain.doFilter(request, response);
    }
}
