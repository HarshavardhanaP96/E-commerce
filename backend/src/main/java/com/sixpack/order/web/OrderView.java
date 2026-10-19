package com.sixpack.order.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.Refund;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.order.domain.StatusEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record OrderView(
        @JsonProperty("_id") String id,
        UserRef user,
        ShippingInfo shippingInfo,
        List<OrderItem> orderItems,
        double subtotal,
        double tax,
        double shippingCharges,
        double discount,
        double total,
        String status,
        Instant createdAt,
        String orderNumber,
        ShippingInfo billingInfo,
        Customer customer,
        List<HistoryView> statusHistory,
        String couponCode,
        String notes,
        PaymentView payment) {

    record UserRef(@JsonProperty("_id") String id, String name) {
    }

    record HistoryView(String status, Instant at, String actor) {
        static HistoryView from(StatusEntry e) {
            return new HistoryView(e.status().wireValue(), e.at(), e.actor());
        }
    }

    record RefundView(double amount, String reason, String status, Instant at) {
        static RefundView from(Refund r) {
            return new RefundView(r.amount(), r.reason(), r.status(), r.at());
        }
    }

    /** Provider order, payment and refund ids stay server-side, and so does {@code failureReason} for customers. */
    record PaymentView(String provider, String status, Double amount, String currency, Instant paidAt,
                       String method, double refunded, List<RefundView> refunds, String failureReason) {
        static PaymentView from(PaymentInfo p, boolean admin) {
            return p == null ? null : new PaymentView(p.provider(), p.status(), p.amount(), p.currency(), p.paidAt(),
                    p.method(), p.refunded(), p.refunds().stream().map(RefundView::from).toList(),
                    admin ? p.failureReason() : null);
        }
    }

    static OrderView from(Order o, Map<String, String> userNames, boolean admin) {
        return new OrderView(o.id(), new UserRef(o.userId(), userNames.getOrDefault(o.userId(), "Deleted user")),
                o.shippingInfo(), o.items(), o.quote().subtotal(), o.quote().tax(), o.quote().shippingCharges(),
                o.quote().discount(), o.quote().total(), o.status().wireValue(), o.createdAt(),
                o.details().orderNumber(), o.details().billingInfo(), o.details().customer(),
                o.details().statusHistory().stream().map(HistoryView::from).toList(), o.details().couponCode(),
                o.details().notes(), PaymentView.from(o.payment(), admin));
    }
}
