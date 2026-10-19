package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixpack.tenant.domain.Tenant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** Products, variants, categories, caches and uploads never cross tenants. */
class TenantCatalogueIsolationIntegrationTest extends TenantIntegrationTest {

    Tenant acme;
    Tenant globex;
    String adminA;
    String adminB;

    @BeforeEach
    void twoTenants() throws Exception {
        acme = createTenant("acme", "Acme", "shop.acme.test");
        globex = createTenant("globex", "Globex", "shop.globex.test");
        adminA = adminTokenFor(acme);
        adminB = adminTokenFor(globex);
    }

    private ResultActions createProductOn(Tenant t, String token, String name, String sku) throws Exception {
        var req = multipart("/api/v1/product/new")
                .file(new MockMultipartFile("photo", "p.png", "image/png", com.sixpack.storage.TestImages.png()));
        req.with(host(hostOf(t)));
        req.param("name", name).param("price", "100").param("stock", "5").param("category", "misc")
                .header("Authorization", bearer(token));
        if (sku != null) {
            req.param("sku", sku);
        }
        return mvc.perform(req);
    }

    private String idOf(ResultActions created) throws Exception {
        return body(created.andExpect(status().isCreated()).andReturn()).get("product").get("_id").asText();
    }

    private ResultActions createCategoryOn(Tenant t, String token, String name) throws Exception {
        return mvc.perform(multipart("/api/v1/category/new").with(host(hostOf(t))).param("name", name)
                .header("Authorization", bearer(token)));
    }

    @Test
    void theSameSlugSkuAndCategoryNameCanExistInBothTenants() throws Exception {
        createProductOn(acme, adminA, "Running Shoe", "SH-1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.product.slug").value("running-shoe"));
        createProductOn(globex, adminB, "Running Shoe", "SH-1").andExpect(status().isCreated())
                .andExpect(jsonPath("$.product.slug").value("running-shoe"));

        createCategoryOn(acme, adminA, "Shoes").andExpect(status().isCreated())
                .andExpect(jsonPath("$.category.slug").value("shoes"));
        createCategoryOn(globex, adminB, "Shoes").andExpect(status().isCreated())
                .andExpect(jsonPath("$.category.slug").value("shoes"));

        // Inside one tenant the rules still hold.
        createProductOn(acme, adminA, "Another", "SH-1").andExpect(status().isConflict());
    }

    @Test
    void productsAndCategoriesAreInvisibleToTheOtherTenant() throws Exception {
        String id = idOf(createProductOn(acme, adminA, "Acme Only", "A-1"));
        createCategoryOn(acme, adminA, "Acme Cat").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/product/" + id).with(host(hostOf(globex)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/product/all").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.products.length()").value(0));
        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.products.length()").value(0));
        mvc.perform(get("/api/v1/product/categories").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.categories.length()").value(0));
        mvc.perform(get("/api/v1/product/admin-products").with(host(hostOf(globex)))
                        .header("Authorization", bearer(adminB)))
                .andExpect(jsonPath("$.products.length()").value(0));
        mvc.perform(get("/api/v1/category").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.categories.length()").value(0));

        mvc.perform(get("/api/v1/product/all").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.products.length()").value(1));
        mvc.perform(get("/api/v1/category").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.categories.length()").value(1));
    }

    @Test
    void anotherTenantsAdminCannotChangeOrDeleteAProduct() throws Exception {
        String id = idOf(createProductOn(acme, adminA, "Acme Only", "A-1"));

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).with(host(hostOf(globex))).param("stock", "999")
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/product/" + id).with(host(hostOf(globex))).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());
        // A token minted for one tenant is useless on the other tenant's host, even for an admin.
        mvc.perform(delete("/api/v1/product/" + id).with(host(hostOf(globex))).header("Authorization", bearer(adminA)))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/product/" + id).with(host(hostOf(acme))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.product.stock").value(5));
    }

    @Test
    void variantSkusAreUniquePerTenant() throws Exception {
        String a = idOf(createProductOn(acme, adminA, "Shirt", null));
        String b = idOf(createProductOn(globex, adminB, "Shirt", null));
        Map<String, Object> body = Map.of(
                "axes", List.of(Map.of("name", "Size", "type", "text", "values", List.of(Map.of("label", "S")))),
                "variants", List.of(Map.of("sku", "TS-S", "price", 499, "stock", 5,
                        "options", List.of(Map.of("name", "Size", "value", "S")))));

        for (Object[] pair : new Object[][] {{acme, adminA, a}, {globex, adminB, b}}) {
            Tenant t = (Tenant) pair[0];
            mvc.perform(put("/api/v1/product/" + pair[2] + "/variants").with(host(hostOf(t)))
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                            .header("Authorization", bearer((String) pair[1])))
                    .andExpect(status().isOk());
        }
        // The other tenant's product id means nothing here.
        mvc.perform(put("/api/v1/product/" + a + "/variants").with(host(hostOf(globex)))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cachedCatalogueDataDoesNotLeakAcrossTenants() throws Exception {
        idOf(createProductOn(acme, adminA, "Acme Only", "A-1"));
        // Warm every cache on acme's side.
        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.products.length()").value(1));
        mvc.perform(get("/api/v1/product/categories").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.categories.length()").value(1));

        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.products.length()").value(0));
        mvc.perform(get("/api/v1/product/categories").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.categories.length()").value(0));

        idOf(createProductOn(globex, adminB, "Globex Only", "G-1"));
        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(globex))))
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].name").value("Globex Only"));
        mvc.perform(get("/api/v1/product/latest").with(host(hostOf(acme))))
                .andExpect(jsonPath("$.products[0].name").value("Acme Only"));
    }

    @Test
    void uploadsAreStoredUnderTheTenantsFolder() throws Exception {
        JsonNode a = body(createProductOn(acme, adminA, "Acme Pic", null).andReturn()).get("product");
        JsonNode b = body(createProductOn(globex, adminB, "Globex Pic", null).andReturn()).get("product");

        assertThat(a.get("photo").asText()).startsWith("uploads/" + acme.id() + "/");
        assertThat(b.get("photo").asText()).startsWith("uploads/" + globex.id() + "/");
    }
}
