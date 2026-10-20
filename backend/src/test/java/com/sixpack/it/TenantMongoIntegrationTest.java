package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.shared.tenant.TenantMongo;
import com.sixpack.shared.tenant.TenantOwned;
import com.sixpack.shared.tenant.TenantRef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class TenantMongoIntegrationTest extends IntegrationTest {

    @Document("tenant_probe")
    static class Probe implements TenantOwned {
        @Id
        String id;
        String tenantId;
        String name;
        String kind;

        Probe(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }

        @Override
        public String getTenantId() {
            return tenantId;
        }

        @Override
        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }

    private static final TenantRef A = new TenantRef("tenant-a", "a");
    private static final TenantRef B = new TenantRef("tenant-b", "b");

    @Autowired
    TenantMongo tenantMongo;

    @BeforeEach
    void cleanProbe() {
        mongo.getCollection("tenant_probe").deleteMany(new org.bson.Document());
    }

    private Probe saveAs(TenantRef tenant, String name, String kind) {
        return TenantContext.callAs(tenant, () -> tenantMongo.save(new Probe(name, kind)));
    }

    private static Query byName(String name) {
        return Query.query(Criteria.where("name").is(name));
    }

    @Test
    void aQueryObjectCanBeReusedAcrossCalls() {
        saveAs(A, "one", "x");
        saveAs(A, "two", "x");
        Query shared = Query.query(Criteria.where("kind").is("x"));

        TenantContext.runAs(A, () -> {
            assertThat(tenantMongo.count(shared, Probe.class)).isEqualTo(2);
            assertThat(tenantMongo.find(shared.limit(1), Probe.class)).hasSize(1);
            assertThat(tenantMongo.exists(shared, Probe.class)).isTrue();
        });
    }

    @Test
    void failsClosedWithoutATenant() {
        try (var none = TenantContext.open(null)) {
            assertThatThrownBy(() -> tenantMongo.find(new Query(), Probe.class))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> tenantMongo.save(new Probe("x", "y"))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void saveStampsTheCurrentTenantAndReadsAreScoped() {
        Probe a = saveAs(A, "shared-name", "x");
        Probe b = saveAs(B, "shared-name", "x");

        assertThat(a.getTenantId()).isEqualTo("tenant-a");
        assertThat(b.getTenantId()).isEqualTo("tenant-b");

        TenantContext.runAs(A, () -> {
            assertThat(tenantMongo.find(new Query(), Probe.class)).extracting(p -> p.id).containsExactly(a.id);
            assertThat(tenantMongo.findById(a.id, Probe.class)).isPresent();
            assertThat(tenantMongo.findById(b.id, Probe.class)).isEmpty();
            assertThat(tenantMongo.findOne(byName("shared-name"), Probe.class)).map(p -> p.id).contains(a.id);
            assertThat(tenantMongo.exists(byName("shared-name"), Probe.class)).isTrue();
            assertThat(tenantMongo.count(new Query(), Probe.class)).isEqualTo(1);
        });
        TenantContext.runAs(B, () -> assertThat(tenantMongo.findById(a.id, Probe.class)).isEmpty());
    }

    @Test
    void anotherTenantsDocumentCannotBeOverwrittenByReusingItsId() {
        Probe a = saveAs(A, "original", "x");

        Probe forged = new Probe("hijacked", "x");
        forged.id = a.id;
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> tenantMongo.save(forged)))
                .isInstanceOf(IllegalStateException.class);

        Probe wrongOwner = new Probe("wrong", "x");
        wrongOwner.setTenantId("tenant-a");
        assertThatThrownBy(() -> TenantContext.runAs(B, () -> tenantMongo.save(wrongOwner)))
                .isInstanceOf(IllegalStateException.class);

        TenantContext.runAs(A, () -> assertThat(tenantMongo.findById(a.id, Probe.class))
                .map(p -> p.name).contains("original"));
    }

    @Test
    void ownersCanUpdateTheirOwnDocuments() {
        Probe a = saveAs(A, "mine", "x");
        TenantContext.runAs(A, () -> {
            a.name = "renamed";
            tenantMongo.save(a);
            assertThat(tenantMongo.findById(a.id, Probe.class)).map(p -> p.name).contains("renamed");
        });
    }

    @Test
    void updatesAndDeletesNeverReachAnotherTenant() {
        saveAs(A, "one", "old");
        saveAs(A, "two", "old");
        Probe b = saveAs(B, "one", "old");

        TenantContext.runAs(A, () -> {
            assertThat(tenantMongo.updateMulti(Query.query(Criteria.where("kind").is("old")),
                    new Update().set("kind", "new"), Probe.class).getModifiedCount()).isEqualTo(2);
            assertThat(tenantMongo.updateFirst(byName("one"), new Update().set("kind", "newer"), Probe.class)
                    .getModifiedCount()).isEqualTo(1);
            assertThat(tenantMongo.remove(byName("two"), Probe.class).getDeletedCount()).isEqualTo(1);
        });

        TenantContext.runAs(B, () -> {
            Probe untouched = tenantMongo.findById(b.id, Probe.class).orElseThrow();
            assertThat(untouched.kind).isEqualTo("old");
            assertThat(tenantMongo.remove(byName("one"), Probe.class).getDeletedCount()).isEqualTo(1);
        });
        TenantContext.runAs(A, () -> assertThat(tenantMongo.count(new Query(), Probe.class)).isEqualTo(1));
    }

    @Test
    void upsertCreatesTheDocumentInTheCurrentTenantOnly() {
        TenantContext.runAs(A, () -> tenantMongo.upsert(byName("counter"), new Update().inc("n", 1), Probe.class));
        TenantContext.runAs(A, () -> tenantMongo.upsert(byName("counter"), new Update().inc("n", 1), Probe.class));
        TenantContext.runAs(B, () -> tenantMongo.upsert(byName("counter"), new Update().inc("n", 5), Probe.class));

        assertThat(mongo.getCollection("tenant_probe").countDocuments()).isEqualTo(2);
        var docA = mongo.getCollection("tenant_probe").find(new org.bson.Document("tenantId", "tenant-a")).first();
        var docB = mongo.getCollection("tenant_probe").find(new org.bson.Document("tenantId", "tenant-b")).first();
        assertThat(docA.get("n")).isEqualTo(2);
        assertThat(docB.get("n")).isEqualTo(5);
    }

    @Test
    void distinctValuesAreScoped() {
        saveAs(A, "a1", "red");
        saveAs(A, "a2", "blue");
        saveAs(B, "b1", "green");

        TenantContext.runAs(A, () -> assertThat(tenantMongo.findDistinct(new Query(), "kind", Probe.class, String.class))
                .containsExactlyInAnyOrder("red", "blue"));
    }

    @Test
    void anUpdateCannotMoveADocumentToAnotherTenant() {
        saveAs(A, "mine", "x");
        TenantContext.runAs(A, () -> {
            assertThatThrownBy(() -> tenantMongo.updateFirst(byName("mine"), new Update().set("tenantId", "tenant-b"),
                    Probe.class)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tenantMongo.upsert(byName("mine"), new Update().setOnInsert("tenantId", "tenant-b"),
                    Probe.class)).isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(List.of(mongo.getCollection("tenant_probe").find().first().get("tenantId"))).containsExactly("tenant-a");
    }
}
