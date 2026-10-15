package com.sixpack.coupon.web;

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
            @NotNull @Positive @DecimalMax("100000") Double amount) {
    }

    record CouponView(@JsonProperty("_id") String id, String coupon, double amount) {
        static CouponView from(Coupon c) {
            return new CouponView(c.id(), c.code(), c.amount());
        }
    }

    private final CouponService service;

    CouponController(CouponService service) {
        this.service = service;
    }

    @GetMapping("/discount")
    Map<String, Object> discount(@RequestParam @Pattern(regexp = CODE_PATTERN, message = "Invalid coupon") String coupon) {
        double amount = service.discountFor(coupon).orElseThrow(() -> ApiException.badRequest("Invalid coupon"));
        return ApiResponse.of("discount", amount);
    }

    @GetMapping("/coupon/all")
    Map<String, Object> all() {
        return ApiResponse.of("coupons", service.list().stream().map(CouponView::from).toList());
    }

    @PostMapping("/coupon/new")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@Valid @RequestBody NewCouponRequest request) {
        Coupon c = service.create(request.coupon(), request.amount());
        return ApiResponse.of("message", "Coupon " + c.code() + " created successfully", "coupon", CouponView.from(c));
    }

    @DeleteMapping("/coupon/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.message("Coupon deleted successfully");
    }
}
