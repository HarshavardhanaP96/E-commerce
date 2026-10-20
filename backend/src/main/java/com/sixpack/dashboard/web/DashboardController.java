package com.sixpack.dashboard.web;

import com.sixpack.dashboard.application.DashboardService;
import com.sixpack.dashboard.application.SummaryService;
import com.sixpack.dashboard.domain.Basis;
import com.sixpack.dashboard.domain.Compare;
import com.sixpack.dashboard.domain.Granularity;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.dashboard.domain.Scope;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {

    private static final int DEFAULT_DAYS = 30;

    private final DashboardService service;
    private final SummaryService summaries;

    DashboardController(DashboardService service, SummaryService summaries) {
        this.service = service;
        this.summaries = summaries;
    }

    /**
     * Headline numbers and trend for {@code from}..{@code to} (inclusive, the shop's local days; default the
     * last 30 days), compared with the previous period, the same period last year, or nothing.
     */
    @GetMapping("/summary")
    Map<String, Object> summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "previous") @Size(max = 10) String compare,
                                @RequestParam(required = false) @Size(max = 10) String granularity,
                                @RequestParam(defaultValue = "net") @Size(max = 10) String basis,
                                @RequestParam(required = false) @Size(max = 64) String productId,
                                @RequestParam(required = false) @Size(max = 50) String category,
                                @RequestParam(required = false) @Size(max = 64) String userId) {
        try {
            LocalDate end = to != null ? to : summaries.today();
            LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
            return ApiResponse.of("summary", summaries.summary(scope(productId, category, userId),
                    Period.of(start, end),
                    granularity == null || granularity.isBlank() ? null : parse(Granularity.class, granularity),
                    parse(Compare.class, compare), parse(Basis.class, basis)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
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

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(type.getSimpleName().toLowerCase(Locale.ROOT) + ": unknown value");
        }
    }

    private static Scope scope(String productId, String category, String userId) {
        return new Scope(blank(productId), category == null || category.isBlank() ? null
                : category.trim().toLowerCase(), blank(userId));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
