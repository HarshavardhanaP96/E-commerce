package com.sixpack.dashboard.web;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.dashboard.application.OrdersService;
import com.sixpack.dashboard.application.OverviewService;
import com.sixpack.dashboard.application.SalesService;
import com.sixpack.dashboard.application.StoreTime;
import com.sixpack.dashboard.domain.Section;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One route per analytics tab. Each answers JSON, or its main table as CSV with {@code ?format=csv}. Every CSV download
 * is audited, like the step-3 exports (ADR-025).
 */
@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
class AnalyticsController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final StoreTime time;
    private final OverviewService overview;
    private final SalesService sales;
    private final OrdersService ordersSection;
    private final AuditTrail audit;

    AnalyticsController(StoreTime time, OverviewService overview, SalesService sales, OrdersService ordersSection,
                        AuditTrail audit) {
        this.time = time;
        this.overview = overview;
        this.sales = sales;
        this.ordersSection = ordersSection;
        this.audit = audit;
    }

    @GetMapping("/overview")
    ResponseEntity<?> overview(@Valid SectionRequest req) {
        return respond("overview", req, overview::section);
    }

    @GetMapping("/sales")
    ResponseEntity<?> sales(@Valid SectionRequest req) {
        return respond("sales", req, sales::section);
    }

    @GetMapping("/orders")
    ResponseEntity<?> orders(@Valid SectionRequest req) {
        return respond("orders", req, ordersSection::section);
    }

    private ResponseEntity<?> respond(String name, SectionRequest req, Function<SectionQuery, ? extends Section> load) {
        boolean csv;
        Section section;
        try {
            csv = req.csv();
            section = load.apply(req.toQuery(time.today()));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (!csv) {
            return ResponseEntity.ok(ApiResponse.of("section", section));
        }
        String body = CsvWriter.write(section.csvHeader(), section.csvRows());
        audit.record(AuditEvent.of(AuditAction.ANALYTICS_EXPORTED).on("report", name));
        String file = name + "-" + section.window().from() + "_" + section.window().to() + ".csv";
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(body);
    }
}
