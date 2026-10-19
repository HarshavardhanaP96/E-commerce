package com.sixpack.order.web;

import com.sixpack.order.application.CheckoutService;
import com.sixpack.order.application.CheckoutService.CartLine;
import com.sixpack.order.application.CheckoutService.ConfirmDetails;
import com.sixpack.order.application.CheckoutService.CheckoutResult;
import com.sixpack.order.application.OrderService;
import com.sixpack.order.domain.Order;
import com.sixpack.order.domain.OrderStatus;
import com.sixpack.order.domain.ShippingInfo;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.web.ApiResponse;
import com.sixpack.user.UserDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/order")
class OrderController {

    record CartLineRequest(@NotBlank @Size(max = 64) String productId, @Size(max = 40) String variantId,
                           @Min(1) @Max(100) int quantity) {
    }

    record CheckoutRequest(@NotEmpty @Size(max = 50) List<@Valid CartLineRequest> items,
                           @Size(max = 32) String coupon) {
    }

    record ShippingRequest(
            @NotBlank @Size(max = 200) String address,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String state,
            @NotBlank @Size(max = 80) String country,
            @NotBlank @Pattern(regexp = "\\d{4,10}", message = "must be 4-10 digits") String pinCode,
            @Size(max = 80) String name,
            @Pattern(regexp = "^\\+?[0-9]{10,13}$|", message = "must be 10-13 digits, optionally starting with +")
            String phone) {
    }

    record ConfirmRequest(
            @NotBlank @Size(max = 100) String providerOrderId,
            @NotBlank @Size(max = 100) String paymentId,
            @NotBlank @Size(max = 256) String signature,
            @NotNull @Valid ShippingRequest shippingInfo,
            @Valid ShippingRequest billingInfo,
            @Size(max = 500) String notes) {
    }

    private final CheckoutService checkout;
    private final OrderService orders;
    private final UserDirectory users;

    OrderController(CheckoutService checkout, OrderService orders, UserDirectory users) {
        this.checkout = checkout;
        this.orders = orders;
        this.users = users;
    }

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> checkout(@Valid @RequestBody CheckoutRequest request, @AuthenticationPrincipal Jwt jwt) {
        List<CartLine> lines = request.items().stream()
                .map(i -> new CartLine(i.productId(), i.variantId(), i.quantity())).toList();
        CheckoutResult r = checkout.checkout(AuthUser.from(jwt).id(), lines, request.coupon());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", r.provider());
        body.put("key", r.key());
        body.put("providerOrderId", r.providerOrderId());
        body.put("amount", r.amountPaise());
        body.put("currency", r.currency());
        body.put("subtotal", r.quote().subtotal());
        body.put("tax", r.quote().tax());
        body.put("shippingCharges", r.quote().shippingCharges());
        body.put("discount", r.quote().discount());
        body.put("total", r.quote().total());
        return ApiResponse.of("checkout", body);
    }

    @PostMapping("/new")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> confirm(@Valid @RequestBody ConfirmRequest request, @AuthenticationPrincipal Jwt jwt) {
        String orderId = checkout.confirm(AuthUser.from(jwt).id(), request.providerOrderId(), request.paymentId(),
                request.signature(), new ConfirmDetails(toDomain(request.shippingInfo()),
                        toDomain(request.billingInfo()), blankToNull(request.notes())));
        return ApiResponse.of("message", "Order placed successfully", "orderId", orderId);
    }

    @GetMapping("/my")
    Map<String, Object> mine(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.of("orders", views(orders.mine(AuthUser.from(jwt).id())));
    }

    @GetMapping("/all")
    Map<String, Object> all(@RequestParam(required = false)
                            @Pattern(regexp = "(?i)processing|shipped|delivered") String status) {
        return ApiResponse.of("orders", views(orders.all(status == null ? null : OrderStatus.fromWire(status))));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Order order = orders.get(id, AuthUser.from(jwt));
        return ApiResponse.of("order", OrderView.from(order, users.namesByIds(List.of(order.userId()))));
    }

    @PutMapping("/{id}")
    Map<String, Object> process(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        Order order = orders.advance(id, AuthUser.from(jwt).id());
        return ApiResponse.of("message", "Order marked as " + order.status().wireValue(),
                "status", order.status().wireValue());
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        orders.delete(id);
        return ApiResponse.message("Order deleted successfully");
    }

    private static ShippingInfo toDomain(ShippingRequest s) {
        if (s == null) {
            return null;
        }
        return new ShippingInfo(s.address().trim(), s.city().trim(), s.state().trim(), s.country().trim(),
                s.pinCode(), blankToNull(s.name()), blankToNull(s.phone()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private List<OrderView> views(List<Order> list) {
        Map<String, String> names = users.namesByIds(list.stream().map(Order::userId).collect(Collectors.toSet()));
        return list.stream().map(o -> OrderView.from(o, names)).toList();
    }
}
