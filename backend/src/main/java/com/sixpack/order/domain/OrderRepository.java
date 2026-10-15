package com.sixpack.order.domain;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    List<Order> findByUser(String userId);

    List<Order> findAll(OrderStatus status);

    void deleteById(String id);
}
