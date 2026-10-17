package com.sixpack.it;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

class CategoryIntegrationTest extends IntegrationTest {

    private String createCategory(String admin, String name, String parentId) throws Exception {
        var req = multipart("/api/v1/category/new").param("name", name).header("Authorization", bearer(admin));
        if (parentId != null) {
            req.param("parentId", parentId);
        }
        MvcResult res = mvc.perform(req).andExpect(status().isCreated()).andReturn();
        return body(res).get("category").get("_id").asText();
    }

    @Test
    void adminCreatesAndAnyoneLists() throws Exception {
        String admin = adminToken();
        createCategory(admin, "Running Shoes", null);

        mvc.perform(get("/api/v1/category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].name").value("Running Shoes"))
                .andExpect(jsonPath("$.categories[0].slug").value("running-shoes"));
    }

    @Test
    void customersCannotWrite() throws Exception {
        String user = register("shopper@test.dev");
        mvc.perform(multipart("/api/v1/category/new").param("name", "X").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateNamesGetDistinctSlugs() throws Exception {
        String admin = adminToken();
        createCategory(admin, "Shoes", null);
        createCategory(admin, "Shoes", null);

        mvc.perform(get("/api/v1/category"))
                .andExpect(jsonPath("$.categories[0].slug").value("shoes"))
                .andExpect(jsonPath("$.categories[1].slug").value("shoes-2"));
    }

    @Test
    void parentMustExist() throws Exception {
        mvc.perform(multipart("/api/v1/category/new").param("name", "Child")
                        .param("parentId", "64b7f0000000000000000000").header("Authorization", bearer(adminToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotDeleteCategoryWithChildren() throws Exception {
        String admin = adminToken();
        String parent = createCategory(admin, "Parent", null);
        createCategory(admin, "Child", parent);

        mvc.perform(delete("/api/v1/category/" + parent).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    @Disabled("enabled in Task 4, once products accept categoryId")
    void cannotDeleteCategoryUsedByProducts() throws Exception {
        String admin = adminToken();
        String category = createCategory(admin, "Used", null);
        mvc.perform(multipart("/api/v1/product/new")
                        .file(new MockMultipartFile("photo", "p.png", "image/png",
                                com.sixpack.storage.TestImages.png()))
                        .param("name", "P").param("price", "10").param("stock", "1").param("category", "x")
                        .param("categoryId", category).header("Authorization", bearer(admin)))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/v1/category/" + category).header("Authorization", bearer(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsParentCycles() throws Exception {
        String admin = adminToken();
        String a = createCategory(admin, "A", null);
        String b = createCategory(admin, "B", a);

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/category/" + a).param("parentId", b)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/category/" + a).param("parentId", a)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesAnUnusedLeaf() throws Exception {
        String admin = adminToken();
        String id = createCategory(admin, "Leaf", null);

        mvc.perform(delete("/api/v1/category/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/category")).andExpect(jsonPath("$.categories.length()").value(0));
    }
}
