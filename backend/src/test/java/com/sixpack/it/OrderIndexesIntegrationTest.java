package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/** The analytics sections read orders by date, by status and by customer; each needs its index. */
class OrderIndexesIntegrationTest extends IntegrationTest {

    @Test
    void ordersHaveTheAnalyticsIndexes() {
        List<String> names = new ArrayList<>();
        for (Document index : mongo.getCollection("orders").listIndexes()) {
            names.add(index.getString("name"));
        }
        assertThat(names).contains("tenant_created", "tenant_status_created", "tenant_user_created");
    }
}
