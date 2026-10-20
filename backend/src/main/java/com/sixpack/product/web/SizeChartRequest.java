package com.sixpack.product.web;

import com.sixpack.product.application.ProductService.SizeChartCommand;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of {@code PUT /product/{id}/size-chart}. Sizes bound the payload; the rules live in {@code SizeChart}. */
record SizeChartRequest(
        @Size(max = 8) List<@Size(max = 100) String> columns,
        @Size(max = 30) List<@Size(max = 8) List<@Size(max = 100) String>> rows,
        @Size(max = 1000) String note) {

    SizeChartCommand toCommand() {
        return new SizeChartCommand(columns, rows, note);
    }
}
