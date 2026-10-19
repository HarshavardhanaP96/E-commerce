package com.sixpack.order.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.AuthUser;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;

    OrderService(OrderRepository orders, ApplicationEventPublisher events, AuditTrail audit) {
        this.orders = orders;
        this.events = events;
        this.audit = audit;
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

    public Order advance(String id, String actorId) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        OrderStatus next = order.status().next();
        if (next == order.status()) {
            return order; // already delivered: nothing to record
        }
        Order saved = orders.save(order.withStatus(next, actorId, Instant.now()));
        events.publishEvent(new OrderChangedEvent(id));
        audit.record(AuditEvent.of(AuditAction.ORDER_STATUS_CHANGED).on("order", id)
                .changing(List.of(FieldChange.of("status", order.status().name(), next.name()))));
        return saved;
    }

    public void delete(String id) {
        orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        orders.deleteById(id);
        events.publishEvent(new OrderChangedEvent(id));
        audit.record(AuditEvent.of(AuditAction.ORDER_DELETED).on("order", id));
    }
}
