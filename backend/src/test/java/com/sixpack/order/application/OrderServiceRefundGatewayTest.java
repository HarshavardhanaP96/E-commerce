package com.sixpack.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sixpack.audit.AuditTrail;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderItem;
import com.sixpack.order.domain.OrderRepository;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.order.domain.PaymentInfo;
import com.sixpack.order.domain.Quote;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.payment.PaymentGateway;
import com.sixpack.payment.PaymentGateways;
import com.sixpack.payment.RefundResult;
import com.sixpack.shared.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A refund must leave through the gateway of the store that took the payment, never through a platform-wide one:
 * with several stores, anything else would pay one store's customers out of another store's account.
 */
class OrderServiceRefundGatewayTest {

    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentGateways gateways = mock(PaymentGateways.class);
    private final PaymentGateway storeGateway = mock(PaymentGateway.class);
    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, mock(ApplicationEventPublisher.class), mock(AuditTrail.class), gateways,
                null);
        when(gateways.current()).thenReturn(storeGateway);
        when(orders.findById("o1")).thenReturn(Optional.of(paidOrder()));
    }

    private static Order paidOrder() {
        return new Order("o1", "u1", new ShippingInfo("1 MG Road", "Pune", "MH", "India", "411001"),
                List.of(new OrderItem("p1", "Tee", "", 250.0, 2)), new Quote(500, 90, 200, 0, 790),
                OrderStatus.PROCESSING,
                new PaymentInfo("razorpay", "po1", "pay1", PaymentInfo.PAID, 790.0, "INR", T0), "2026-10-16", T0);
    }

    @Test
    void refundsGoThroughTheCurrentStoresGateway() {
        when(storeGateway.provider()).thenReturn("razorpay");
        when(orders.reserveRefund(eq("o1"), anyLong(), anyLong())).thenReturn(true);
        when(storeGateway.refund(eq("pay1"), eq(79_000L), anyString(), any())).thenReturn(new RefundResult("rfnd_1",
                "processed"));

        service.refund("o1", null, "damaged", "admin1");

        verify(gateways).current();
        verify(storeGateway).refund(eq("pay1"), eq(79_000L), anyString(), eq("damaged"));
    }

    @Test
    void anOrderPaidThroughAnotherProviderIsJudgedAgainstTheStoresOwnGateway() {
        when(storeGateway.provider()).thenReturn("fake");

        assertThatThrownBy(() -> service.refund("o1", null, "damaged", "admin1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("paid through razorpay");

        assertThat(storeGateway.provider()).isEqualTo("fake");
        verify(storeGateway, never()).refund(anyString(), anyLong(), anyString(), any());
        verify(orders, never()).reserveRefund(anyString(), anyLong(), anyLong());
    }
}
