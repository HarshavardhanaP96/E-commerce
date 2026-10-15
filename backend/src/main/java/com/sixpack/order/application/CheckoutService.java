package com.sixpack.order.application;

import com.sixpack.coupon.CouponLookup;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.PaymentIntent;
import com.sixpack.order.domain.PaymentIntentRepository;
import com.sixpack.order.domain.PriceCalculator;
import com.sixpack.order.domain.Quote;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.ProviderOrder;
import com.sixpack.product.ProductCatalog;
import com.sixpack.product.ProductSnapshot;
import com.sixpack.shared.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Checkout in two steps (ADR-009):
 * <ol>
 *   <li>{@link #checkout}: price the cart from the catalogue, open a provider order, store a payment intent.</li>
 *   <li>{@link #confirm}: verify the provider signature, consume the intent once, reserve stock, create the order.</li>
 * </ol>
 * The client never supplies prices, totals or the user id.
 */
@Service
public class CheckoutService {

    public record CartLine(String productId, int quantity) {
    }

    public record CheckoutResult(String provider, String key, String providerOrderId, long amountPaise,
                                 String currency, Quote quote) {
    }

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final ProductCatalog catalog;
    private final CouponLookup coupons;
    private final PaymentGateway gateway;
    private final PaymentIntentRepository intents;
    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;

    CheckoutService(ProductCatalog catalog, CouponLookup coupons, PaymentGateway gateway,
                    PaymentIntentRepository intents, OrderRepository orders, ApplicationEventPublisher events,
                    MeterRegistry meters) {
        this.catalog = catalog;
        this.coupons = coupons;
        this.gateway = gateway;
        this.intents = intents;
        this.orders = orders;
        this.events = events;
        this.meters = meters;
    }

    public CheckoutResult checkout(String userId, List<CartLine> lines, String coupon) {
        Map<String, Integer> quantities = mergeLines(lines);
        Map<String, ProductSnapshot> products = catalog.findSnapshots(quantities.keySet()).stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));

        List<OrderItem> items = quantities.entrySet().stream().map(line -> {
            ProductSnapshot p = products.get(line.getKey());
            if (p == null) {
                throw ApiException.badRequest("A product in your cart no longer exists");
            }
            if (p.stock() < line.getValue()) {
                throw ApiException.conflict("Only " + p.stock() + " left in stock for " + p.name());
            }
            return new OrderItem(p.id(), p.name(), p.photo(), p.price(), line.getValue());
        }).toList();

        double discount = 0;
        String normalizedCoupon = coupon == null || coupon.isBlank() ? null : coupon.trim();
        if (normalizedCoupon != null) {
            discount = coupons.discountFor(normalizedCoupon)
                    .orElseThrow(() -> ApiException.badRequest("Invalid coupon"));
        }

        Quote quote = PriceCalculator.quote(items, discount);
        if (quote.totalPaise() < 100) {
            throw ApiException.badRequest("Order total must be at least ₹1");
        }
        ProviderOrder providerOrder = gateway.createOrder(quote.totalPaise(), "rcpt_" + System.currentTimeMillis());
        intents.save(new PaymentIntent(null, userId, gateway.provider(), providerOrder.id(), items,
                normalizedCoupon, quote));
        return new CheckoutResult(gateway.provider(), gateway.publicKey(), providerOrder.id(),
                providerOrder.amountPaise(), providerOrder.currency(), quote);
    }

    public String confirm(String userId, String providerOrderId, String paymentId, String signature,
                          ShippingInfo shipping) {
        PaymentIntent intent = intents.findByProviderOrderId(providerOrderId)
                .filter(i -> i.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Checkout session not found"));
        if (!gateway.verifySignature(providerOrderId, paymentId, signature)) {
            meters.counter("sixpack.checkout.failed", "reason", "bad_signature").increment();
            throw ApiException.badRequest("Payment verification failed");
        }
        if (!intents.markConsumed(providerOrderId)) {
            throw ApiException.conflict("This payment has already been used for an order");
        }

        Map<String, Integer> quantities = intent.items().stream()
                .collect(Collectors.toMap(OrderItem::productId, OrderItem::quantity, Integer::sum));
        try {
            catalog.reserveStock(quantities);
        } catch (ApiException e) {
            intents.markCreated(providerOrderId);
            meters.counter("sixpack.checkout.failed", "reason", "paid_but_unfulfilled").increment();
            log.error("paid_but_unfulfilled providerOrderId={} paymentId={} userId={}: {}",
                    providerOrderId, paymentId, userId, e.getMessage());
            throw e;
        }

        Order order;
        try {
            order = orders.save(new Order(null, userId, shipping, intent.items(), intent.quote(),
                    OrderStatus.PROCESSING, new PaymentInfo(intent.provider(), providerOrderId, paymentId), null));
        } catch (RuntimeException e) {
            catalog.releaseStock(quantities);
            intents.markCreated(providerOrderId);
            throw e;
        }
        meters.counter("sixpack.orders.placed").increment();
        events.publishEvent(new OrderChangedEvent(order.id()));
        return order.id();
    }

    private static Map<String, Integer> mergeLines(List<CartLine> lines) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (CartLine line : lines) {
            merged.merge(line.productId(), line.quantity(), Integer::sum);
        }
        if (merged.values().stream().anyMatch(q -> q > 100)) {
            throw ApiException.badRequest("Maximum quantity per product is 100");
        }
        return merged;
    }
}
