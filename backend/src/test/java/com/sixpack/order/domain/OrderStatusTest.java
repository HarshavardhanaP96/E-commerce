package com.sixpack.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderStatusTest {

    @Test
    void advancesThroughLifecycleAndStopsAtDelivered() {
        assertThat(OrderStatus.PROCESSING.next()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(OrderStatus.SHIPPED.next()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(OrderStatus.DELIVERED.next()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void parsesLegacyWireValues() {
        assertThat(OrderStatus.fromWire("Shipped")).isEqualTo(OrderStatus.SHIPPED);
        assertThat(OrderStatus.fromWire("delivered")).isEqualTo(OrderStatus.DELIVERED);
    }
}
