package com.sixpack.order.application;

import com.sixpack.coupon.CustomerHistory;
import com.sixpack.order.domain.OrderRepository;
import org.springframework.stereotype.Component;

/** Lets the coupon module ask whether a customer has ordered before, without depending on this module's internals. */
@Component
class OrderCustomerHistory implements CustomerHistory {

    private final OrderRepository orders;

    OrderCustomerHistory(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public boolean hasPlacedOrder(String userId) {
        return orders.hasAnyOrder(userId);
    }
}
