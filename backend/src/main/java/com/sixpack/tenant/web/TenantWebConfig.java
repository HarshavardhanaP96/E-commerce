package com.sixpack.tenant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.tenant.application.TenantRegistry;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TenantWebConfig {

    /** Just ahead of the Spring Security filter chain, and after the forwarded-header filter. */
    @Bean
    FilterRegistrationBean<TenantResolutionFilter> tenantResolutionFilter(TenantRegistry registry,
                                                                          ObjectMapper mapper) {
        FilterRegistrationBean<TenantResolutionFilter> registration =
                new FilterRegistrationBean<>(new TenantResolutionFilter(registry, mapper));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
