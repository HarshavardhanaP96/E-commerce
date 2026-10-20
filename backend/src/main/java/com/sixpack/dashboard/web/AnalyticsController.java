package com.sixpack.dashboard.web;

import com.sixpack.dashboard.application.StoreTime;
import com.sixpack.dashboard.domain.Section;
import com.sixpack.dashboard.domain.SectionQuery;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.web.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One route per analytics tab. Each answers JSON, or its main table as CSV with {@code ?format=csv}. */
@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
class AnalyticsController {

    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final StoreTime time;

    AnalyticsController(StoreTime time) {
        this.time = time;
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
        String file = name + "-" + section.window().from() + "_" + section.window().to() + ".csv";
        return ResponseEntity.ok().contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file).build().toString())
                .body(CsvWriter.write(section.csvHeader(), section.csvRows()));
    }
}
