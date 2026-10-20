package com.sixpack.tenant.application;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Suspends trial stores whose trial has ended. Runs on the same interval as the offboarding purge. */
@Service
public class TenantTrials {

    private final TenantAdminService admin;

    TenantTrials(TenantAdminService admin) {
        this.admin = admin;
    }

    @Scheduled(initialDelayString = "${app.tenant.purge-interval:PT1H}",
            fixedDelayString = "${app.tenant.purge-interval:PT1H}")
    void scheduled() {
        expireDue(Instant.now());
    }

    public void expireDue(Instant now) {
        admin.expireTrials(now);
    }
}
