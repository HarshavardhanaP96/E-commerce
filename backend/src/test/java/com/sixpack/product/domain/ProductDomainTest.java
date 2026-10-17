package com.sixpack.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.shared.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductDomainTest {

    @Test
    void slugsAreLowercaseAsciiAndDashed() {
        assertThat(Slugs.of("Running Shoe")).isEqualTo("running-shoe");
        assertThat(Slugs.of("  Café  Crème!! ")).isEqualTo("cafe-creme");
        assertThat(Slugs.of("C++ Book (2nd ed.)")).isEqualTo("c-book-2nd-ed");
    }

    @Test
    void slugsFallBackAndAreCapped() {
        assertThat(Slugs.of("***")).isEqualTo("item");
        assertThat(Slugs.of(null)).isEqualTo("item");
        assertThat(Slugs.of("a".repeat(200))).hasSize(80);
    }

    @Test
    void emptyDetailsAreActiveWithEmptyLists() {
        ProductDetails d = ProductDetails.EMPTY;
        assertThat(d.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(d.tags()).isEmpty();
        assertThat(d.images()).isEmpty();
        assertThat(d.slug()).isNull();
    }

    @Test
    void detailsDefaultNullStatusAndLists() {
        ProductDetails d = new ProductDetails(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(d).isEqualTo(ProductDetails.EMPTY);
    }

    @Test
    void legacySevenArgConstructorGetsEmptyDetails() {
        Product p = new Product("1", "Shoe", "uploads/a.png", 10, 2, "footwear", null);
        assertThat(p.details()).isEqualTo(ProductDetails.EMPTY);
        assertThat(p.withDetails(new ProductDetails("s", null, null, null, null, null, null, null,
                List.of("x"), null, null)).details().slug()).isEqualTo("s");
    }

    @Test
    void statusParsingAndVisibility() {
        assertThat(ProductStatus.parse(" draft ")).isEqualTo(ProductStatus.DRAFT);
        assertThat(ProductStatus.fromStored(null)).isEqualTo(ProductStatus.ACTIVE);
        assertThat(ProductStatus.fromStored("garbage")).isEqualTo(ProductStatus.ACTIVE);
        assertThat(ProductStatus.ACTIVE.isPublic()).isTrue();
        assertThat(ProductStatus.DRAFT.isPublic()).isFalse();
        assertThat(ProductStatus.ARCHIVED.isPublic()).isFalse();
        assertThatThrownBy(() -> ProductStatus.parse("bogus")).isInstanceOf(ApiException.class);
    }
}
