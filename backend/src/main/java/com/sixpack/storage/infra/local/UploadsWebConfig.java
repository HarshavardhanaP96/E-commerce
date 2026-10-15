package com.sixpack.storage.infra.local;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves stored images at {@code /uploads/**}. Spring Security adds {@code X-Content-Type-Options: nosniff}. */
@Configuration
class UploadsWebConfig implements WebMvcConfigurer {

    private final LocalFileStorage storage;

    UploadsWebConfig(LocalFileStorage storage) {
        this.storage = storage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(storage.root().toUri().toString() + "/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
    }
}
