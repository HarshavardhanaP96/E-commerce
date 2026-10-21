package com.sixpack.dashboard.web;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.dashboard.application.SectionsService;
import com.sixpack.dashboard.application.SummaryService;
import com.sixpack.dashboard.domain.Period;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shop dashboard's sections (analytics step 3) for {@code from}..{@code to}: the store's local days, inclusive,
 * default the last 30 days. Each table also downloads as CSV, and every download is audited because the customer
 * table holds personal data.
 */
@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardSectionsController {

    private static final int DEFAULT_DAYS = 30;
    private static final int TOP = 20;

    private final SectionsService sections;
    private final SummaryService summaries;
    private final AuditTrail audit;

    DashboardSectionsController(SectionsService sections, SummaryService summaries, AuditTrail audit) {
        this.sections = sections;
        this.summaries = summaries;
        this.audit = audit;
    }

    @GetMapping("/products")
    Map<String, Object> products(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of("products", sections.products(period(from, to), TOP));
    }

    @GetMapping("/customers")
    Map<String, Object> customers(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of("customers", sections.customers(period(from, to), TOP));
    }

    @GetMapping("/coupons")
    Map<String, Object> coupons(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of("coupons", sections.coupons(period(from, to)));
    }

    @GetMapping("/payments")
    Map<String, Object> payments(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of("payments", sections.payments(period(from, to)));
    }

    @GetMapping("/tax")
    Map<String, Object> tax(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of("tax", sections.tax(period(from, to)));
    }

    @GetMapping(value = "/export/{table}", produces = "text/csv")
    ResponseEntity<String> export(@PathVariable @Size(max = 32) String table,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!SectionCsv.exists(table)) {
            throw ApiException.notFound("No such report: " + table);
        }
        Period period = period(from, to);
        String csv = SectionCsv.write(table, sections, period);
        audit.record(AuditEvent.of(AuditAction.ANALYTICS_EXPORTED).on("report", table));
        String file = table + "_" + period.from() + "_" + period.to() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }

    private Period period(LocalDate from, LocalDate to) {
        try {
            LocalDate end = to != null ? to : summaries.today();
            return Period.of(from != null ? from : end.minusDays(DEFAULT_DAYS - 1L), end);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }
}
