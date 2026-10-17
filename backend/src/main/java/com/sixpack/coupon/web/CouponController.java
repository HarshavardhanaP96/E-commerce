package com.sixpack.coupon.web;

import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.time.Instant;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.coupon.domain.CouponRules;
import com.sixpack.coupon.application.CouponService.UpdateCommand;
import com.sixpack.coupon.application.CouponService.CreateCommand;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.coupon.application.CouponService;
import com.sixpack.coupon.domain.Coupon;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/payment")
class CouponController {

    private static final String CODE_PATTERN = "[A-Za-z0-9_-]{3,32}";

    record NewCouponRequest(
            @NotBlank @Pattern(regexp = CODE_PATTERN, message = "3-32 letters, digits, _ or -") String coupon,
            @NotNull @Positive @DecimalMax("100000") Double amount,
            @Pattern(regexp = "(?i)flat|percent|", message = "must be FLAT or PERCENT") String type,
            @Positive @DecimalMax("100000") Double maxDiscount,
            @PositiveOrZero @DecimalMax("10000000") Double minOrderValue,
            Instant startsAt,
            Instant expiresAt,
            @Min(1) @Max(10_000_000) Integer usageLimit,
            @Min(1) @Max(1000) Integer perUserLimit,
            Boolean active) {
    }

    record UpdateCouponRequest(
            @Positive @DecimalMax("100000") Double amount,
            @Positive @DecimalMax("100000") Double maxDiscount,
            @PositiveOrZero @DecimalMax("10000000") Double minOrderValue,
            Instant startsAt,
            Instant expiresAt,
            @Min(1) @Max(10_000_000) Integer usageLimit,
            @Min(1) @Max(1000) Integer perUserLimit,
            Boolean active) {
    }

    record CouponView(@JsonProperty("_id") String id, String coupon, double amount, String type, Double maxDiscount,
                      Double minOrderValue, Instant startsAt, Instant expiresAt, Integer usageLimit, int usedCount,
                      Integer perUserLimit, boolean active, String status) {
        static CouponView from(Coupon c) {
            CouponRules r = c.rules();
            return new CouponView(c.id(), c.code(), c.amount(), r.type().name(), r.maxDiscount(), r.minOrderValue(),
                    r.startsAt(), r.expiresAt(), r.usageLimit(), c.usedCount(), r.perUserLimit(), r.active(),
                    c.status(Instant.now()));
        }
    }

    private final CouponService service;

    CouponController(CouponService service) {
        this.service = service;
    }

    @GetMapping("/discount")
    Map<String, Object> discount(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "Invalid coupon") String coupon,
            @RequestParam(required = false) @PositiveOrZero @DecimalMax("100000000") Double subtotal,
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt == null ? null : AuthUser.from(jwt).id();
        return ApiResponse.of("discount", service.discountFor(coupon, subtotal == null ? 0 : subtotal, userId));
    }

    @PostMapping("/coupon/new")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@Valid @RequestBody NewCouponRequest r) {
        Coupon c = service.create(new CreateCommand(r.coupon(), r.amount(), r.type(), r.maxDiscount(),
                r.minOrderValue(), r.startsAt(), r.expiresAt(), r.usageLimit(), r.perUserLimit(), r.active()));
        return ApiResponse.of("message", "Coupon " + c.code() + " created successfully", "coupon", CouponView.from(c));
    }

    @PutMapping("/coupon/{id}")
    Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCouponRequest r) {
        Coupon c = service.update(id, new UpdateCommand(r.amount(), r.maxDiscount(), r.minOrderValue(), r.startsAt(),
                r.expiresAt(), r.usageLimit(), r.perUserLimit(), r.active()));
        return ApiResponse.of("message", "Coupon " + c.code() + " updated successfully", "coupon", CouponView.from(c));
    }

    @GetMapping("/coupon/all")
    Map<String, Object> all() {
        return ApiResponse.of("coupons", service.list().stream().map(CouponView::from).toList());
    }


    @DeleteMapping("/coupon/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.message("Coupon deleted successfully");
    }
}
