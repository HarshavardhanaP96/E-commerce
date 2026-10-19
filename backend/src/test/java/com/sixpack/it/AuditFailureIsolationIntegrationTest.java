package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.audit.domain.AuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class AuditFailureIsolationIntegrationTest extends IntegrationTest {

    @Autowired
    MeterRegistry meters;
    @MockitoSpyBean
    AuditRepository repository;

    @Test
    void anAuditWriteFailureDoesNotFailAProductUpdate() throws Exception {
        String admin = adminToken();
        String id = createProduct(admin, "Lamp", 400, 3, "home");
        double before = meters.counter("sixpack.audit.write", "result", "failure").count();
        doThrow(new RuntimeException("db down")).when(repository).append(any());

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).param("price", "450")
                        .param("stock", "3").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        double price = body(mvc.perform(get("/api/v1/product/" + id)).andExpect(status().isOk()).andReturn())
                .get("product").get("price").asDouble();
        assertThat(price).isEqualTo(450.0);
        assertThat(meters.counter("sixpack.audit.write", "result", "failure").count()).isEqualTo(before + 1);
    }
}
