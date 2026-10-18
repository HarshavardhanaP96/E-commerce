package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.shared.tenant.BackfillTarget;
import com.sixpack.tenant.application.DefaultTenantBootstrap;
import com.sixpack.tenant.application.TenantBackfill;
import com.sixpack.tenant.domain.Tenant;
import com.sixpack.tenant.domain.TenantStatus;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Import(TenantBootstrapIntegrationTest.ProbeTargets.class)
class TenantBootstrapIntegrationTest extends TenantIntegrationTest {

    @TestConfiguration
    static class ProbeTargets {
        @Bean
        BackfillTarget probeTarget() {
            return () -> "backfill_probe";
        }
    }

    @Autowired
    DefaultTenantBootstrap bootstrap;

    @Autowired
    TenantBackfill backfill;

    @BeforeEach
    void cleanProbe() {
        mongo.getCollection("backfill_probe").deleteMany(new Document());
    }

    @Test
    void theDefaultTenantExistsOnceWithTheConfiguredDomains() {
        Tenant first = tenantRepository.findBySlug("default").orElseThrow();
        assertThat(first.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(first.name()).isEqualTo("6Pack");
        assertThat(first.domains()).containsExactly("localhost", "127.0.0.1");

        bootstrap.ensureDefault();
        bootstrap.ensureDefault();

        assertThat(tenantRepository.findAll().stream().filter(t -> t.slug().equals("default"))).hasSize(1);
        assertThat(tenantRepository.findBySlug("default").orElseThrow().id()).isEqualTo(first.id());
    }

    @Test
    void hostsResolveCaseInsensitivelyAndIgnoreThePort() {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        assertThat(tenantRegistry.resolve("LocalHost")).map(Tenant::id).contains(def.id());
        assertThat(tenantRegistry.resolve("localhost:8081")).map(Tenant::id).contains(def.id());
        assertThat(tenantRegistry.resolve("localhost.")).map(Tenant::id).contains(def.id());
    }

    @Test
    void whileOnlyTheDefaultTenantExistsEveryHostResolvesToIt() {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        assertThat(tenantRegistry.resolve("anything.example.org")).map(Tenant::id).contains(def.id());
    }

    @Test
    void aSecondTenantTurnsUnknownHostsIntoMisses() {
        Tenant acme = createTenant("acme", "Acme Shop", "shop.acme.test");
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();

        assertThat(tenantRegistry.resolve("shop.acme.test")).map(Tenant::id).contains(acme.id());
        assertThat(tenantRegistry.resolve("localhost")).map(Tenant::id).contains(def.id());
        assertThat(tenantRegistry.resolve("anything.example.org")).isEmpty();
        assertThat(tenantRegistry.byId(acme.id()).name()).isEqualTo("Acme Shop");
    }

    @Test
    void backfillAssignsOnlyUnownedDocumentsToTheDefaultTenantAndIsRepeatable() {
        Tenant def = tenantRepository.findBySlug("default").orElseThrow();
        mongo.getCollection("backfill_probe").insertOne(new Document("name", "old-1"));
        mongo.getCollection("backfill_probe").insertOne(new Document("name", "old-2"));
        mongo.getCollection("backfill_probe").insertOne(new Document("name", "owned").append("tenantId", "someone-else"));

        assertThat(backfill.backfill()).isEqualTo(2);
        assertThat(backfill.backfill()).isZero();

        assertThat(mongo.getCollection("backfill_probe").countDocuments(new Document("tenantId", def.id()))).isEqualTo(2);
        assertThat(mongo.getCollection("backfill_probe").find(new Document("name", "owned")).first().get("tenantId"))
                .isEqualTo("someone-else");
    }
}
