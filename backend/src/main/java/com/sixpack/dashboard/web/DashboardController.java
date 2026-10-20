package com.sixpack.dashboard.web;

import com.sixpack.dashboard.application.DashboardService;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {

    private final DashboardService service;

    DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/stats")
    Map<String, Object> stats(@RequestParam(required = false) @Size(max = 64) String productId,
                              @RequestParam(required = false) @Size(max = 50) String category,
                              @RequestParam(required = false) @Size(max = 64) String userId) {
        return ApiResponse.of("stats", service.stats(scope(productId, category, userId)));
    }

    @GetMapping("/pie")
    Map<String, Object> pie(@RequestParam(required = false) @Size(max = 64) String productId,
                            @RequestParam(required = false) @Size(max = 50) String category,
                            @RequestParam(required = false) @Size(max = 64) String userId) {
        return ApiResponse.of("charts", service.pie(scope(productId, category, userId)));
    }

    @GetMapping("/bar")
    Map<String, Object> bar(@RequestParam(required = false) @Size(max = 64) String productId,
                            @RequestParam(required = false) @Size(max = 50) String category,
                            @RequestParam(required = false) @Size(max = 64) String userId) {
        return ApiResponse.of("charts", service.bar(scope(productId, category, userId)));
    }

    @GetMapping("/line")
    Map<String, Object> line(@RequestParam(required = false) @Size(max = 64) String productId,
                             @RequestParam(required = false) @Size(max = 50) String category,
                             @RequestParam(required = false) @Size(max = 64) String userId) {
        return ApiResponse.of("charts", service.line(scope(productId, category, userId)));
    }

    /** Units and revenue of one product per variant, best sellers first. */
    @GetMapping("/products/{productId}/variants")
    Map<String, Object> variantSales(@PathVariable @Size(max = 64) String productId) {
        return ApiResponse.of("sales", service.variantSales(productId));
    }

    private static Scope scope(String productId, String category, String userId) {
        return new Scope(blank(productId), category == null || category.isBlank() ? null
                : category.trim().toLowerCase(), blank(userId));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
