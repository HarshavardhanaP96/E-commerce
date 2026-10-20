package com.sixpack.dashboard.web;

import com.sixpack.dashboard.application.SectionsService;
import com.sixpack.dashboard.domain.Period;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/** One CSV per section table, with every row (the page shows the top rows only). */
final class SectionCsv {

    private record Table(List<String> header, BiFunction<SectionsService, Period, List<List<Object>>> rows) {
    }

    private static final Map<String, Table> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("top-products", new Table(List.of("product_id", "product", "units", "revenue", "orders"),
                (s, p) -> s.products(p, 0).top().stream()
                        .map(r -> row(r.productId(), text(r.name()), r.units(), r.revenue(), r.orders())).toList()));
        TABLES.put("categories", new Table(List.of("category", "revenue", "units", "share_percent"),
                (s, p) -> s.products(p, 0).categories().stream()
                        .map(r -> row(text(r.category()), r.revenue(), r.units(), r.share())).toList()));
        TABLES.put("stock", new Table(List.of("product_id", "product", "variant_id", "variant", "stock", "threshold",
                "units_sold", "days_left"),
                (s, p) -> s.products(p, 0).stock().stream().map(r -> row(r.productId(), text(r.name()),
                        r.variantId(), text(r.label()), r.stock(), r.threshold(), r.unitsSold(), r.daysLeft())).toList()));
        TABLES.put("idle-products", new Table(List.of("product_id", "product", "stock"),
                (s, p) -> s.products(p, 0).idle().stream()
                        .map(r -> row(r.productId(), text(r.name()), r.stock())).toList()));
        TABLES.put("top-customers", new Table(List.of("customer_id", "name", "email", "orders", "revenue"),
                (s, p) -> s.customers(p, 0).top().stream().map(r -> row(r.userId(), text(r.name()), text(r.email()),
                        r.orders(), r.revenue())).toList()));
        TABLES.put("places", new Table(List.of("state", "city", "orders", "revenue"),
                (s, p) -> s.customers(p, 0).places().stream()
                        .map(r -> row(text(r.state()), text(r.city()), r.orders(), r.revenue())).toList()));
        TABLES.put("coupons", new Table(List.of("code", "uses", "discount", "revenue"),
                (s, p) -> s.coupons(p).codes().stream()
                        .map(r -> row(text(r.code()), r.uses(), r.discount(), r.revenue())).toList()));
        TABLES.put("payment-methods", new Table(List.of("method", "orders", "amount", "share_percent"),
                (s, p) -> s.payments(p).methods().stream()
                        .map(r -> row(text(r.method()), r.orders(), r.amount(), r.share())).toList()));
        TABLES.put("refunds", new Table(List.of("period_start", "refunds", "amount"),
                (s, p) -> s.payments(p).refunds().stream()
                        .map(r -> row(r.start().toString(), r.count(), r.amount())).toList()));
        TABLES.put("tax", new Table(List.of("month", "gst_rate", "taxable_value", "cgst", "sgst", "igst", "total_tax"),
                (s, p) -> s.tax(p).rows().stream().map(r -> row(r.month(), r.rate(), r.taxableValue(),
                        r.cgst(), r.sgst(), r.igst(), r.tax())).toList()));
    }

    private SectionCsv() {
    }

    static boolean exists(String table) {
        return TABLES.containsKey(table);
    }

    static String write(String table, SectionsService service, Period period) {
        Table t = TABLES.get(table);
        StringWriter out = new StringWriter();
        out.write('﻿'); // the byte-order mark makes Excel read the file as UTF-8 (₹, accents)
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setRecordSeparator("\n")
                .setHeader(t.header().toArray(String[]::new)).build())) {
            for (List<Object> row : t.rows().apply(service, period)) {
                List<String> cells = new ArrayList<>();
                for (Object cell : row) {
                    cells.add(cell == null ? "" : cell instanceof Number n ? number(n) : cell.toString());
                }
                csv.printRecord(cells);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** Allows nulls, unlike List.of. */
    private static List<Object> row(Object... cells) {
        return Arrays.asList(cells);
    }

    /** A spreadsheet must not run a name such as "=HYPERLINK(...)" as a formula. */
    private static String text(String value) {
        if (value == null) {
            return null;
        }
        return !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String number(Number n) {
        if (n instanceof Integer || n instanceof Long) {
            return n.toString();
        }
        return BigDecimal.valueOf(n.doubleValue()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
    }
}
