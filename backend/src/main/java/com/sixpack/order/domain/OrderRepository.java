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

    /** Removes the street address and PIN code from the user's orders; amounts and state stay for tax records. */
    void anonymiseShippingForUser(String userId);
}
