package com.sixpack.order.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * What an invoice shows, derived from an order and never stored: it can't drift from the order, and an erased
 * account's invoice simply loses the buyer's details along with the order's.
 *
 * <p>Tax is grouped by rate exactly as {@link PriceCalculator} charged it (whole rupees per rate), so the summary adds
 * up to the order's tax. Within the seller's state the tax is shown as half CGST and half SGST; elsewhere as IGST.
 */
public record Invoice(String number, Instant date, String orderNumber, Seller seller, Buyer buyer, ShippingInfo billTo,
                      ShippingInfo shipTo, List<Line> lines, List<TaxLine> taxSummary, double subtotal,
                      double discount, double shippingCharges, double tax, double total, String paymentMethod,
                      String paymentStatus, double refunded) {

    public record Seller(String name, String address, String gstin, String state, String email, String phone) {
    }

    public record Buyer(String name, String email, String phone) {
    }

    public record Line(String description, String sku, int quantity, double unitPrice, double amount,
                       double taxRate) {
    }

    public record TaxLine(double rate, double taxableValue, double cgst, double sgst, double igst) {
    }

    private static final double DEFAULT_TAX_RATE = PriceCalculator.DEFAULT_TAX_RATE_PERCENT;

    public static Invoice of(Order order, Seller seller) {
        OrderDetails details = order.details();
        String suffix = details.orderNumber() != null && details.orderNumber().startsWith("ORD-")
                ? details.orderNumber().substring(4) : order.id();
        ShippingInfo billTo = details.billingInfo() != null ? details.billingInfo() : order.shippingInfo();
        boolean sameState = sameState(seller.state(), billTo == null ? null : billTo.state());

        List<Line> lines = new ArrayList<>();
        Map<Double, Long> paiseByRate = new TreeMap<>();
        for (OrderItem item : order.items()) {
            double rate = item.taxRate() == null ? DEFAULT_TAX_RATE : item.taxRate();
            long amountPaise = PriceCalculator.toPaise(item.price()) * item.quantity();
            paiseByRate.merge(rate, amountPaise, Long::sum);
            String description = item.variantLabel() == null ? item.name()
                    : item.name() + " (" + item.variantLabel() + ")";
            lines.add(new Line(description, item.sku(), item.quantity(), item.price(), amountPaise / 100.0, rate));
        }
        List<TaxLine> summary = new ArrayList<>();
        for (Map.Entry<Double, Long> group : paiseByRate.entrySet()) {
            double tax = Math.round(group.getValue() * (group.getKey() / 100.0) / 100.0);
            summary.add(sameState
                    ? new TaxLine(group.getKey(), group.getValue() / 100.0, tax / 2, tax / 2, 0)
                    : new TaxLine(group.getKey(), group.getValue() / 100.0, 0, 0, tax));
        }

        PaymentInfo payment = order.payment();
        Quote q = order.quote();
        Customer customer = details.customer();
        Instant date = payment != null && payment.paidAt() != null ? payment.paidAt() : order.createdAt();
        return new Invoice("INV-" + suffix, date, details.orderNumber(), seller,
                customer == null ? null : new Buyer(customer.name(), customer.email(), customer.phone()), billTo,
                order.shippingInfo(), lines, summary, q.subtotal(), q.discount(), q.shippingCharges(), q.tax(),
                q.total(), payment == null ? null : payment.method(), payment == null ? null : payment.status(),
                payment == null ? 0 : payment.refunded());
    }

    private static boolean sameState(String a, String b) {
        return a != null && b != null && !a.isBlank() && a.trim().toLowerCase(Locale.ROOT)
                .equals(b.trim().toLowerCase(Locale.ROOT));
    }
}
