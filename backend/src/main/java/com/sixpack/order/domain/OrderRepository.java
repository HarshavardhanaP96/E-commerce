package com.sixpack.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    List<Order> findByUser(String userId);

    List<Order> findAll(OrderStatus status);

    void deleteById(String id);

    boolean hasOpenOrders(String userId);

    boolean hasAnyOrder(String userId);

    /**
     * Sets aside {@code amountPaise} of the payment for a refund that is about to be sent to the provider, unless
     * that would refund more than {@code totalPaise} in all. Atomic, so two admins cannot both refund the same money.
     *
     * @return false if the order is unpaid, unknown, or the amount no longer fits
     */
    boolean reserveRefund(String orderId, long amountPaise, long totalPaise);

    /** Gives a reservation back after the provider refused the refund, and notes why. */
    void releaseRefund(String orderId, long amountPaise, String failureReason);

    /** Records a refund the provider accepted and moves the payment to PARTIALLY_REFUNDED or REFUNDED. */
    void completeRefund(String orderId, Refund refund, long totalPaise);

    /**
     * Removes everything that identifies the person (street address, PIN code, recipient name and phone, billing
     * address, contact snapshot, notes) from the user's orders; amounts, items, order number, history and payment
     * status stay for tax records.
     */
    void anonymiseShippingForUser(String userId);

    /** Ids of paid orders created in [from, to) with an analytics or marketing consent, oldest first. */
    List<String> findConsentedIdsBetween(Instant from, Instant to, int limit);
}
