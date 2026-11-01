package com.sixpack.order.application;

import com.sixpack.order.PaidOrders;
import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class OrderPaidOrders implements PaidOrders {

    private final OrderRepository orders;

    OrderPaidOrders(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<PaidOrder> find(String orderId) {
        return orders.findById(orderId).filter(o -> o.payment() != null && o.payment().status() != null)
                .map(OrderPaidOrders::paid);
    }

    @Override
    public List<String> consentedBetween(Instant from, Instant to, int limit) {
        return orders.findConsentedIdsBetween(from, to, limit);
    }

    private static PaidOrder paid(Order o) {
        Customer c = o.details().customer();
        return new PaidOrder(o.id(), o.details().orderNumber(), o.userId(), o.createdAt(), o.quote().total(),
                o.quote().tax(), o.quote().shippingCharges(), o.payment().currency(), o.details().couponCode(),
                o.items().stream().map(i -> new PaidOrders.Line(i.productId(), i.name(), i.variantLabel(), i.price(),
                        i.quantity())).toList(),
                c == null ? null : c.email(), c == null ? null : c.phone(), o.details().attribution());
    }
}
