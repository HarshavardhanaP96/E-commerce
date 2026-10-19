package com.sixpack.consent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class ConsentWebConfig implements WebMvcConfigurer {

    private final AdminAgreementInterceptor adminAgreement;

    ConsentWebConfig(AdminAgreementInterceptor adminAgreement) {
        this.adminAgreement = adminAgreement;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAgreement).addPathPatterns("/api/v1/**");
    }
}
