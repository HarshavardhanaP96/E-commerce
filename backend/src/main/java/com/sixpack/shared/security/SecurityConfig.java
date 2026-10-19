package com.sixpack.shared.security;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class SecurityConfig {

    private static final String ADMIN = "ADMIN";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProperties props, JsonSecurityHandlers handlers,
                                           AccountAccess accounts) throws Exception {
        http
                // Stateless bearer-token API: no cookies, so CSRF protection is not applicable.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsSource(props)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        // ops & docs
                        .requestMatchers("/", "/error", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(GET, "/uploads/**").permitAll()
                        // user
                        .requestMatchers(POST, "/api/v1/user/register", "/api/v1/user/login",
                                "/api/v1/user/forgot-password", "/api/v1/user/reset-password").permitAll()
                        .requestMatchers("/api/v1/user/me", "/api/v1/user/me/**").authenticated()
                        .requestMatchers("/api/v1/user/**").hasRole(ADMIN)
                        // legal & consent
                        .requestMatchers(GET, "/api/v1/legal/**").permitAll()
                        .requestMatchers("/api/v1/consent/**", "/api/v1/consent").authenticated()
                        // product
                        .requestMatchers(GET, "/api/v1/product/admin-products").hasRole(ADMIN)
                        .requestMatchers(GET, "/api/v1/product/**").permitAll()
                        .requestMatchers("/api/v1/product/**").hasRole(ADMIN)
                        .requestMatchers(GET, "/api/v1/category", "/api/v1/category/**").permitAll()
                        .requestMatchers("/api/v1/category", "/api/v1/category/**").hasRole(ADMIN)
                        // coupons
                        .requestMatchers(GET, "/api/v1/payment/discount").permitAll()
                        .requestMatchers("/api/v1/payment/coupon/**").hasRole(ADMIN)
                        // orders
                        .requestMatchers(POST, "/api/v1/order/quote").permitAll()
                        .requestMatchers(GET, "/api/v1/order/all").hasRole(ADMIN)
                        .requestMatchers(PUT, "/api/v1/order/*").hasRole(ADMIN)
                        .requestMatchers(DELETE, "/api/v1/order/*").hasRole(ADMIN)
                        .requestMatchers("/api/v1/order/**").authenticated()
                        // audit log
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
                        // dashboard
                        .requestMatchers("/api/v1/dashboard/**").hasRole(ADMIN)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDenied()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDenied()))
                .addFilterBefore(new AuthRateLimitFilter(props.authRequestsPerMinute(), handlers),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new AccountStatusFilter(accounts, handlers), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Verifies the $2b$ hashes written by the old Node backend (bcrypt cost 10).
        return new BCryptPasswordEncoder();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(AuthUser.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static CorsConfigurationSource corsSource(SecurityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
