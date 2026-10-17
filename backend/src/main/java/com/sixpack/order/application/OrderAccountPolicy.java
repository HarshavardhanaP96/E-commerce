package com.sixpack.order.application;

import com.sixpack.order.domain.OrderRepository;
import com.sixpack.user.AccountDeletionGuard;
import com.sixpack.user.UserDeletedEvent;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** How orders take part in account deletion: wait for open orders, then strip the delivery address. */
@Component
class OrderAccountPolicy implements AccountDeletionGuard {

    private final OrderRepository orders;

    OrderAccountPolicy(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<String> blockReason(String userId) {
        return orders.hasOpenOrders(userId)
                ? Optional.of("This account has orders that haven't been delivered yet. "
                        + "It can be deleted once they arrive.")
                : Optional.empty();
    }

    @EventListener
    public void onUserDeleted(UserDeletedEvent event) {
        orders.anonymiseShippingForUser(event.userId());
    }
}
