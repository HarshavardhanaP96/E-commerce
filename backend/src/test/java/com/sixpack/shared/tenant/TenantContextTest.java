package com.sixpack.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TenantContextTest {

    private static final TenantRef A = new TenantRef("a-id", "a");
    private static final TenantRef B = new TenantRef("b-id", "b");

    @Test
    void emptyByDefaultAndRequireFailsClosed() {
        assertThat(TenantContext.current()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
    }

    @Test
    void scopesNestAndRestoreThePreviousTenant() {
        try (var outer = TenantContext.open(A)) {
            assertThat(TenantContext.require()).isEqualTo(A);
            try (var inner = TenantContext.open(B)) {
                assertThat(TenantContext.require()).isEqualTo(B);
            }
            assertThat(TenantContext.require()).isEqualTo(A);
        }
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void contextIsClearedEvenWhenTheActionFails() {
        assertThatThrownBy(() -> TenantContext.runAs(A, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void callAsReturnsTheActionsValueUnderTheGivenTenant() {
        assertThat(TenantContext.callAs(B, () -> TenantContext.require().slug())).isEqualTo("b");
    }

    @Test
    void contextDoesNotLeakAcrossThreads() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var scope = TenantContext.open(A)) {
                var other = CompletableFuture.supplyAsync(() -> TenantContext.current().isPresent(), executor).get();
                assertThat(other).isFalse();
                var asB = CompletableFuture.supplyAsync(
                        () -> TenantContext.callAs(B, () -> TenantContext.require().slug()), executor).get();
                assertThat(asB).isEqualTo("b");
                assertThat(TenantContext.require()).isEqualTo(A);
            }
        }
    }

    @Test
    void propertiesApplyDefaultsAndNormaliseDomains() {
        TenantProperties blank = new TenantProperties(null, " ", null);
        assertThat(blank.defaultSlug()).isEqualTo("default");
        assertThat(blank.defaultName()).isEqualTo("6Pack");
        assertThat(blank.defaultDomains()).containsExactly("localhost", "127.0.0.1");

        TenantProperties custom = new TenantProperties("main", "Main Store", List.of(" Shop.Example.COM ", "", "x.test"));
        assertThat(custom.defaultSlug()).isEqualTo("main");
        assertThat(custom.defaultDomains()).containsExactly("shop.example.com", "x.test");
    }

    @Test
    void theTenantSlugIsOnEveryLogLineWhileBound() {
        assertThat(MDC.get(TenantContext.MDC_KEY)).isNull();
        try (var outer = TenantContext.open(A)) {
            assertThat(MDC.get("tenant")).isEqualTo("a");
            try (var inner = TenantContext.open(B)) {
                assertThat(MDC.get("tenant")).isEqualTo("b");
            }
            assertThat(MDC.get("tenant")).isEqualTo("a");
            try (var none = TenantContext.open(null)) {
                assertThat(MDC.get("tenant")).isNull();
            }
            assertThat(MDC.get("tenant")).isEqualTo("a");
        }
        assertThat(MDC.get("tenant")).isNull();
    }

    @Test
    void metricTagNamesTheBoundTenantOrNone() {
        assertThat(TenantContext.metricTag()).isEqualTo(io.micrometer.core.instrument.Tag.of("tenant", "none"));
        TenantContext.runAs(A, () -> assertThat(TenantContext.metricTag().getValue()).isEqualTo("a"));
    }
}
