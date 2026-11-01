package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.product.CatalogReads;
import com.sixpack.product.CatalogReads.Listing;
import com.sixpack.shared.tenant.TenantContext;
import com.sixpack.tenant.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The product module's read port for the feed: sellable lines of public products, one product at a time. */
class CatalogReadsIntegrationTest extends FeedIntegrationTest {

    @Autowired
    CatalogReads catalog;

    Tenant acme;

    @BeforeEach
    void store() {
        acme = feedStore("acme", "Acme Shop", "INR", null);
    }

    private List<Listing> listings(Tenant t, int maxProducts) {
        List<Listing> out = new ArrayList<>();
        TenantContext.runAs(ref(t), () -> catalog.forEachListing(maxProducts, out::add));
        return out;
    }

    @Test
    void oneLinePerSimpleProductAndPerActiveVariantOldestFirst() {
        String mug = insertProduct(acme, simple("Mug", 250, 4).append("description", "Big").append("brand", "Potter"));
        insertProduct(acme, simple("Draft", 1, 1).append("status", "DRAFT"));
        insertProduct(acme, simple("Archived", 1, 1).append("status", "ARCHIVED"));
        String legacy = insertProduct(acme, simple("Legacy", 5, 1).append("status", null));
        String tee = insertProduct(acme, tee(variant("tee-s", "S", 499, 5, "ACTIVE"),
                variant("tee-m", "M", 549, 0, "DISABLED")));

        assertThat(listings(acme, 100)).containsExactly(
                new Listing(mug, null, "Mug", null, "Big", "Potter", "uploads/p/Mug.png", 250, 4),
                new Listing(legacy, null, "Legacy", null, null, null, "uploads/p/Legacy.png", 5, 1),
                new Listing(tee, "tee-s", "Tee", "S / Black", null, null, "uploads/p/tee-black.png", 499, 5));
    }

    @Test
    void readsAtMostTheGivenNumberOfProducts() {
        String one = insertProduct(acme, simple("One", 1, 1));
        String two = insertProduct(acme, simple("Two", 1, 1));
        insertProduct(acme, simple("Three", 1, 1));

        assertThat(listings(acme, 2)).extracting(Listing::productId).containsExactly(one, two);
    }

    @Test
    void readsOnlyTheCurrentStore() {
        Tenant globex = feedStore("globex", "Globex", "INR", null);
        String mine = insertProduct(acme, simple("Anvil", 1, 1));
        insertProduct(globex, simple("Gizmo", 1, 1));

        assertThat(listings(acme, 100)).extracting(Listing::productId).containsExactly(mine);
        try (var none = TenantContext.open(null)) {
            assertThatThrownBy(() -> catalog.forEachListing(100, l -> { }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
