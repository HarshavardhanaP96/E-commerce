package com.sixpack.tenant.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Platform default limits for every store that has not been given its own. */
@ConfigurationProperties("app.tenant.limits")
public record LimitProperties(Integer products, DataSize uploads, Integer requestsPerMinute) {

    public LimitProperties {
        products = products == null || products <= 0 ? 5_000 : products;
        uploads = uploads == null || uploads.toBytes() <= 0 ? DataSize.ofGigabytes(2) : uploads;
        requestsPerMinute = requestsPerMinute == null || requestsPerMinute <= 0 ? 600 : requestsPerMinute;
    }
}
