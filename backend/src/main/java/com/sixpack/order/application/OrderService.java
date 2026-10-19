package com.sixpack.order.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.Refund;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.RefundResult;
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
    private final PaymentGateway gateway;

    OrderService(OrderRepository orders, ApplicationEventPublisher events, AuditTrail audit,
                 PaymentGateway gateway) {
        this.orders = orders;
        this.events = events;
        this.audit = audit;
        this.gateway = gateway;
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

    /**
     * Returns money to the customer through the provider that took it.
     *
     * @param amount rupees to return, or null for everything still refundable
     * @param reason shown to the customer; kept out of the audit log because admins may type names into it
     */
    public Order refund(String id, Double amount, String reason, String actorId) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        PaymentInfo payment = order.payment();
        if (payment == null || payment.paymentId() == null || payment.status() == null) {
            throw ApiException.conflict("This order has no online payment to refund");
        }
        if (!gateway.provider().equals(payment.provider())) {
            throw ApiException.conflict("This order was paid through " + payment.provider()
                    + ", so it has to be refunded there");
        }
        if (PaymentInfo.REFUNDED.equals(payment.status()) || payment.refundablePaise() <= 0) {
            throw ApiException.conflict("This order has already been fully refunded");
        }
        long paise = amount == null ? payment.refundablePaise() : Math.round(amount * 100);
        if (paise <= 0) {
            throw ApiException.badRequest("amount: must be positive");
        }
        if (paise > payment.refundablePaise()) {
            throw ApiException.badRequest("Only ₹" + rupees(payment.refundablePaise())
                    + " can still be refunded on this order");
        }
        if (!orders.reserveRefund(id, paise, payment.totalPaise())) {
            throw ApiException.conflict("Another refund is being processed for this order, please try again");
        }
        RefundResult result;
        try {
            // Same state and amount give the same key, so retrying after a timeout can't pay out twice.
            result = gateway.refund(payment.paymentId(), paise,
                    "rfnd_" + id + "_" + payment.refundedPaise() + "_" + paise, reason);
        } catch (RuntimeException e) {
            orders.releaseRefund(id, paise, "Refund of ₹" + rupees(paise) + " failed: " + e.getMessage());
            throw e;
        }
        orders.completeRefund(id, new Refund(result.id(), paise / 100.0, reason, result.status(), actorId,
                Instant.now()), payment.totalPaise());
        events.publishEvent(new OrderChangedEvent(id));
        audit.record(AuditEvent.of(AuditAction.ORDER_REFUNDED).on("order", id)
                .changing(List.of(FieldChange.of("refunded", payment.refunded(),
                        (payment.refundedPaise() + paise) / 100.0))));
        return orders.findById(id).orElseThrow();
    }

    private static String rupees(long paise) {
        return paise % 100 == 0 ? String.valueOf(paise / 100) : String.format("%.2f", paise / 100.0);
    }

    public void delete(String id) {
        orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        orders.deleteById(id);
        events.publishEvent(new OrderChangedEvent(id));
        audit.record(AuditEvent.of(AuditAction.ORDER_DELETED).on("order", id));
    }
}
