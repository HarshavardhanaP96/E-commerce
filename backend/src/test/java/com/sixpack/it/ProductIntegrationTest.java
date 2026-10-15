package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

class ProductIntegrationTest extends IntegrationTest {

    @Test
    void crudAndCatalogue() throws Exception {
        String admin = adminToken();
        String shoe = createProduct(admin, "Running Shoe", 1999, 5, " Footwear ");
        createProduct(admin, "Laptop", 55000, 2, "Electronics");

        mvc.perform(get("/api/v1/product/" + shoe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.category").value("footwear"))
                .andExpect(jsonPath("$.product.photo").value(org.hamcrest.Matchers.startsWith("uploads/")));
        mvc.perform(get("/api/v1/product/categories"))
                .andExpect(jsonPath("$.categories.length()").value(2));
        mvc.perform(get("/api/v1/product/all").param("category", "footwear"))
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.totalPage").value(1));
        mvc.perform(get("/api/v1/product/all").param("price", "2000").param("sort", "asc"))
                .andExpect(jsonPath("$.products[0].name").value("Running Shoe"));

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + shoe).param("stock", "9")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.stock").value(9))
                .andExpect(jsonPath("$.product.name").value("Running Shoe"));
    }

    @Test
    void deleteRemovesOnlyTheRequestedProduct() throws Exception {
        String admin = adminToken();
        String a = createProduct(admin, "A", 10, 1, "misc");
        String b = createProduct(admin, "B", 10, 1, "misc");
        mvc.perform(delete("/api/v1/product/" + b).header("Authorization", bearer(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/product/" + a)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/product/" + b)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsNonImageUploadsEvenWithImageNames() throws Exception {
        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "evil.png", "image/png",
                                "<html><script>alert(1)</script></html>".getBytes()))
                        .param("name", "X").param("price", "10").param("stock", "1").param("category", "x")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchTreatsInputLiterally() throws Exception {
        createProduct(adminToken(), "C++ Book (2nd ed.)", 500, 3, "books");
        mvc.perform(get("/api/v1/product/all").param("search", "c++ book (2nd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(1));
        mvc.perform(get("/api/v1/product/all").param("search", "(a+)+$"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(0));
    }

    @Test
    void invalidIdsReturn404NotServerError() throws Exception {
        mvc.perform(get("/api/v1/product/not-an-object-id")).andExpect(status().isNotFound());
    }
}
