package com.sixpack.order.domain;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    List<Order> findByUser(String userId);

    List<Order> findAll(OrderStatus status);

    void deleteById(String id);

    boolean hasOpenOrders(String userId);

    boolean hasAnyOrder(String userId);

    /**
     * Removes everything that identifies the person (street address, PIN code, recipient name and phone, billing
     * address, contact snapshot, notes) from the user's orders; amounts, items, order number, history and payment
     * status stay for tax records.
     */
    void anonymiseShippingForUser(String userId);
}
