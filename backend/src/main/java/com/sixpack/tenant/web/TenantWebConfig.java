package com.sixpack.tenant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixpack.shared.tenant.TenantQuotas;
import com.sixpack.tenant.application.TenantRegistry;
import io.micrometer.core.instrument.MeterRegistry;
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

    /** Right after tenant resolution, still ahead of Spring Security, so a flood never reaches authentication. */
    @Bean
    FilterRegistrationBean<TenantRateLimitFilter> tenantRateLimitFilter(TenantQuotas quotas, ObjectMapper mapper,
                                                                         MeterRegistry meters) {
        FilterRegistrationBean<TenantRateLimitFilter> registration =
                new FilterRegistrationBean<>(new TenantRateLimitFilter(quotas, mapper, meters));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 5);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** Picked up by Spring Boot's web MVC observation filter in place of the default convention. */
    @Bean
    TenantObservationConvention tenantObservationConvention() {
        return new TenantObservationConvention();
    }
}
