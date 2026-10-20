package com.sixpack.order.application;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import com.sixpack.consent.ConsentLedger;
import com.sixpack.consent.Purpose;
import com.sixpack.coupon.CouponLine;
import com.sixpack.coupon.CouponLookup;
import com.sixpack.order.OrderChangedEvent;
import com.sixpack.order.domain.Customer;
import com.sixpack.order.domain.OrderDetails;
import com.sixpack.order.domain.OrderNumbers;
import com.sixpack.order.domain.StatusEntry;
import com.sixpack.user.UserContact;
import com.sixpack.user.UserDirectory;
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
import com.sixpack.payment.PaymentGateways;
import com.sixpack.payment.ProviderOrder;
import com.sixpack.product.ProductCatalog;
import com.sixpack.product.ProductSnapshot;
import com.sixpack.product.StockLine;
import com.sixpack.shared.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.time.Instant;
import java.util.Map;
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

    /** @param variantId null for a product without variants */
    public record CartLine(String productId, String variantId, int quantity) {

        public CartLine(String productId, int quantity) {
            this(productId, null, quantity);
        }
    }

    /** A cart line's identity: the same product in two variants is two lines. */
    private record LineKey(String productId, String variantId) {
    }

    public record ConfirmDetails(ShippingInfo shipping, ShippingInfo billing, String notes) {
    }

    public record CheckoutResult(String provider, String key, String providerOrderId, long amountPaise,
                                 String currency, Quote quote) {
    }

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final ProductCatalog catalog;
    private final CouponLookup coupons;
    private final PaymentGateways gateways;
    private final PaymentIntentRepository intents;
    private final OrderRepository orders;
    private final ConsentLedger consents;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final UserDirectory users;
    private final AuditTrail audit;

    CheckoutService(ProductCatalog catalog, CouponLookup coupons, PaymentGateways gateways,
                    PaymentIntentRepository intents, OrderRepository orders, ConsentLedger consents,
                    ApplicationEventPublisher events, MeterRegistry meters, UserDirectory users,
                    AuditTrail audit) {
        this.catalog = catalog;
        this.coupons = coupons;
        this.gateways = gateways;
        this.intents = intents;
        this.orders = orders;
        this.consents = consents;
        this.events = events;
        this.meters = meters;
        this.users = users;
        this.audit = audit;
    }

    /** A cart priced from the catalogue. {@code coupon} is the normalised code that was applied, or null. */
    public record PricedCart(List<OrderItem> items, Quote quote, String coupon) {
    }

    /**
     * Prices a cart without touching payment or stock, so the cart page shows what checkout will charge.
     *
     * @param userId the customer, or null for an anonymous preview (per-customer coupon rules are then skipped)
     */
    public PricedCart price(String userId, List<CartLine> lines, String coupon) {
        Map<LineKey, Integer> quantities = mergeLines(lines);

        List<OrderItem> items = new ArrayList<>();
        List<CouponLine> couponLines = new ArrayList<>();
        for (Map.Entry<LineKey, Integer> line : quantities.entrySet()) {
            ProductSnapshot p = catalog.resolveLine(line.getKey().productId(), line.getKey().variantId());
            String what = p.variantLabel() == null ? p.name() : p.name() + " (" + p.variantLabel() + ")";
            if (p.stock() < line.getValue()) {
                throw ApiException.conflict("Only " + p.stock() + " left in stock for " + what);
            }
            items.add(new OrderItem(p.id(), p.name(), p.photo(), p.price(), line.getValue(), p.sku(), p.taxRate(),
                    p.variantId(), p.variantLabel(),
                    p.options().stream().map(o -> new OrderItem.Option(o.name(), o.value())).toList()));
            couponLines.add(new CouponLine(p.id(), p.category(), p.price() * line.getValue()));
        }

        double discount = 0;
        String normalizedCoupon = coupon == null || coupon.isBlank() ? null : coupon.trim();
        if (normalizedCoupon != null) {
            discount = coupons.discountFor(normalizedCoupon, couponLines, userId);
        }
        return new PricedCart(items, PriceCalculator.quote(items, discount), normalizedCoupon);
    }

    public CheckoutResult checkout(String userId, List<CartLine> lines, String coupon) {
        // Checked before money moves, so a paid order is never refused for a missing acceptance.
        if (!consents.hasAcceptedCurrent(userId, Purpose.TERMS)) {
            throw ApiException.forbidden("Please accept the updated Terms & Conditions before placing an order");
        }
        PricedCart priced = price(userId, lines, coupon);
        Quote quote = priced.quote();
        if (quote.totalPaise() < 100) {
            throw ApiException.badRequest("Order total must be at least ₹1");
        }
        PaymentGateway gateway = gateways.current();
        ProviderOrder providerOrder = gateway.createOrder(quote.totalPaise(), "rcpt_" + System.currentTimeMillis());
        intents.save(new PaymentIntent(null, userId, gateway.provider(), providerOrder.id(), priced.items(),
                priced.coupon(), quote, providerOrder.currency()));
        return new CheckoutResult(gateway.provider(), gateway.publicKey(), providerOrder.id(),
                providerOrder.amountPaise(), providerOrder.currency(), quote);
    }

    public String confirm(String userId, String providerOrderId, String paymentId, String signature,
                          ConfirmDetails details) {
        PaymentIntent intent = intents.findByProviderOrderId(providerOrderId)
                .filter(i -> i.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Checkout session not found"));
        if (!gateways.current().verifySignature(providerOrderId, paymentId, signature)) {
            meters.counter("sixpack.checkout.failed", "reason", "bad_signature").increment();
            throw ApiException.badRequest("Payment verification failed");
        }
        UserContact contact = users.contactById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
        if (!intents.markConsumed(providerOrderId)) {
            throw ApiException.conflict("This payment has already been used for an order");
        }

        Map<LineKey, Integer> merged = intent.items().stream().collect(Collectors.toMap(
                i -> new LineKey(i.productId(), i.variantId()), OrderItem::quantity, Integer::sum));
        List<StockLine> quantities = merged.entrySet().stream()
                .map(e -> new StockLine(e.getKey().productId(), e.getKey().variantId(), e.getValue())).toList();
        try {
            catalog.reserveStock(quantities);
        } catch (ApiException e) {
            intents.markCreated(providerOrderId);
            meters.counter("sixpack.checkout.failed", "reason", "paid_but_unfulfilled").increment();
            log.error("paid_but_unfulfilled providerOrderId={} paymentId={} userId={}: {}",
                    providerOrderId, paymentId, userId, e.getMessage());
            throw e;
        }

        Instant now = Instant.now();
        String method = paymentMethod(paymentId);
        Order order;
        boolean couponRedeemed = false;
        try {
            if (intent.coupon() != null) {
                coupons.redeem(intent.coupon(), userId);
                couponRedeemed = true;
            }
            order = orders.save(new Order(null, userId, details.shipping(), intent.items(), intent.quote(),
                    OrderStatus.PROCESSING,
                    new PaymentInfo(intent.provider(), providerOrderId, paymentId, "PAID", intent.quote().total(),
                            intent.currency() == null ? "INR" : intent.currency(), now, method, null, 0, null),
                    consents.currentVersion(Purpose.TERMS), null,
                    new OrderDetails(OrderNumbers.generate(now, ThreadLocalRandom.current()), details.billing(),
                            new Customer(contact.name(), contact.email(), contact.phone()),
                            List.of(new StatusEntry(OrderStatus.PROCESSING, now, userId)), intent.coupon(),
                            details.notes())));
        } catch (RuntimeException e) {
            if (couponRedeemed) {
                coupons.release(intent.coupon(), userId);
            }
            catalog.releaseStock(quantities);
            intents.markCreated(providerOrderId);
            throw e;
        }
        meters.counter("sixpack.orders.placed").increment();
        events.publishEvent(new OrderChangedEvent(order.id()));
        audit.record(AuditEvent.of(AuditAction.ORDER_PLACED).on("order", order.id())
                .changing(List.of(FieldChange.of("status", null, OrderStatus.PROCESSING.name()),
                        FieldChange.of("total", null, intent.quote().total()))));
        return order.id();
    }

    /** How the customer paid. The money is already taken, so a provider hiccup must not fail the order. */
    private String paymentMethod(String paymentId) {
        try {
            return gateways.current().fetchPayment(paymentId).method();
        } catch (RuntimeException e) {
            log.warn("Could not look up the payment method for {}: {}", paymentId, e.getMessage());
            return null;
        }
    }

    private static Map<LineKey, Integer> mergeLines(List<CartLine> lines) {
        Map<LineKey, Integer> merged = new LinkedHashMap<>();
        for (CartLine line : lines) {
            String variantId = line.variantId() == null || line.variantId().isBlank() ? null : line.variantId().trim();
            merged.merge(new LineKey(line.productId(), variantId), line.quantity(), Integer::sum);
        }
        if (merged.values().stream().anyMatch(q -> q > 100)) {
            throw ApiException.badRequest("Maximum quantity per product is 100");
        }
        return merged;
    }
}
