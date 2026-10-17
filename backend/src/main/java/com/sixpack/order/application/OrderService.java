package com.sixpack.order.application;

import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.AuthUser;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;

    OrderService(OrderRepository orders, ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    public List<Order> mine(String userId) {
        return orders.findByUser(userId);
    }

    public List<Order> all(OrderStatus status) {
        return orders.findAll(status);
    }

    public Order get(String id, AuthUser caller) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!caller.admin() && !order.userId().equals(caller.id())) {
            // 404 rather than 403 so order ids cannot be probed
            throw ApiException.notFound("Order not found");
        }
        return order;
    }

    public Order advance(String id) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        Order saved = orders.save(order.withStatus(order.status().next(), "system", java.time.Instant.now()));
        events.publishEvent(new OrderChangedEvent(id));
        return saved;
    }

    public void delete(String id) {
        orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        orders.deleteById(id);
        events.publishEvent(new OrderChangedEvent(id));
    }
}
