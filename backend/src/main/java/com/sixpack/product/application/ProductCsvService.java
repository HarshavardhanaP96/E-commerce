package com.sixpack.product.application;

import static com.sixpack.shared.config.CacheConfig.PRODUCTS_ADMIN;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_BY_ID;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_CATEGORIES;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_LATEST;
import static com.sixpack.shared.config.CacheConfig.PRODUCTS_OPTIONS;

import com.sixpack.product.ProductChangedEvent;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import com.sixpack.product.domain.ProductRepository;
import com.sixpack.product.domain.ProductStatus;
import com.sixpack.product.domain.ProductVariants;
import com.sixpack.product.domain.Variant;
import com.sixpack.product.domain.VariantRules;
import com.sixpack.product.domain.VariantStatus;
import com.sixpack.shared.ApiException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Bulk price/stock editing through a spreadsheet. One row per product without variants and one per variant.
 * Import only updates existing lines (price, MRP, stock, status, low-stock threshold); a blank cell keeps the
 * current value. The whole file is checked before anything is written.
 */
@Service
public class ProductCsvService {

    public static final List<String> HEADERS = List.of("product_id", "product_name", "variant_id", "variant", "sku",
            "price", "mrp", "stock", "status", "low_stock_threshold");

    public static final int MAX_BYTES = 1_000_000;
    public static final int MAX_ROWS = 5_000;
    private static final double MAX_PRICE = 10_000_000;

    public record Problem(int line, String message) {
    }

    /** What one row changes, e.g. ["price 499 → 549", "stock 3 → 10"]. */
    public record Change(int line, String productId, String product, String variant, List<String> changes) {
    }

    public record ImportResult(boolean applied, List<Change> changes, int unchanged, List<Problem> problems) {
    }

    /** The planned edit of one line; null fields mean "keep". */
    private record RowPlan(int line, Product product, Variant variant, Double price, Double mrp, Integer stock,
                           String status) {
    }

    private final ProductRepository products;
    private final ApplicationEventPublisher events;

    ProductCsvService(ProductRepository products, ApplicationEventPublisher events) {
        this.products = products;
        this.events = events;
    }

    public String export() {
        StringWriter out = new StringWriter();
        // The byte-order mark makes Excel read the file as UTF-8 (₹, accents).
        out.write('﻿');
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                .setHeader(HEADERS.toArray(String[]::new)).build())) {
            for (Product p : products.findAll()) {
                ProductDetails d = p.details();
                String threshold = d.lowStockThreshold() == null ? "" : String.valueOf(d.lowStockThreshold());
                if (!p.hasVariants()) {
                    csv.printRecord(p.id(), text(p.name()), "", "", text(d.sku()), number(p.price()),
                            number(d.mrp()), p.stock(), d.status().name(), threshold);
                }
                for (Variant v : d.variants().items()) {
                    csv.printRecord(p.id(), text(p.name()), v.id(), text(v.label()), text(v.sku()),
                            number(v.price()), number(v.mrp()), v.stock(), v.status().name(), threshold);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /**
     * @param dryRun true to report what would change without writing anything
     * @return problems (nothing written), or the changes (written unless {@code dryRun})
     */
    @CacheEvict(cacheNames = {PRODUCTS_LATEST, PRODUCTS_CATEGORIES, PRODUCTS_OPTIONS, PRODUCTS_ADMIN,
            PRODUCTS_BY_ID}, allEntries = true)
    public ImportResult importCsv(byte[] file, boolean dryRun) {
        if (file == null || file.length == 0) {
            throw ApiException.badRequest("file: choose a CSV file");
        }
        if (file.length > MAX_BYTES) {
            throw ApiException.badRequest("file: at most 1 MB");
        }
        String content = new String(file, java.nio.charset.StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }

        List<Problem> problems = new ArrayList<>();
        List<RowPlan> plans = new ArrayList<>();
        Map<String, Integer> thresholds = new LinkedHashMap<>();
        Map<String, Integer> thresholdLines = new HashMap<>();
        List<CSVRecord> records = parse(content);
        if (records.size() > MAX_ROWS) {
            throw ApiException.badRequest("file: at most " + MAX_ROWS + " rows");
        }

        Map<String, Product> byId = new HashMap<>();
        Map<String, String[]> bySku = new HashMap<>();   // sku -> {productId, variantId or null}
        for (Product p : products.findAll()) {
            byId.put(p.id(), p);
            if (p.details().sku() != null) {
                bySku.put(p.details().sku(), new String[] {p.id(), null});
            }
            p.details().variants().items().forEach(v -> bySku.put(v.sku(), new String[] {p.id(), v.id()}));
        }

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < records.size(); i++) {
            CSVRecord r = records.get(i);
            int line = i + 2;   // line 1 is the header
            try {
                RowPlan plan = plan(r, line, byId, bySku);
                String key = plan.product().id() + ":" + (plan.variant() == null ? "" : plan.variant().id());
                if (!seen.add(key)) {
                    throw ApiException.badRequest("this product/variant appears on an earlier row too");
                }
                Integer threshold = integer(r, "low_stock_threshold", 0, VariantRules.MAX_STOCK);
                if (threshold != null) {
                    Integer before = thresholds.putIfAbsent(plan.product().id(), threshold);
                    if (before != null && !before.equals(threshold)) {
                        throw ApiException.badRequest("low_stock_threshold differs from line "
                                + thresholdLines.get(plan.product().id()) + " for the same product");
                    }
                    thresholdLines.putIfAbsent(plan.product().id(), line);
                }
                plans.add(plan);
            } catch (ApiException e) {
                problems.add(new Problem(line, e.getMessage()));
            }
        }
        if (!problems.isEmpty()) {
            return new ImportResult(false, List.of(), 0, problems);
        }

        List<Change> changes = new ArrayList<>();
        int unchanged = 0;
        Set<String> thresholdReported = new HashSet<>();
        for (RowPlan plan : plans) {
            List<String> diff = describe(plan);
            Integer threshold = thresholds.get(plan.product().id());
            if (threshold != null && !threshold.equals(plan.product().details().lowStockThreshold())
                    && thresholdReported.add(plan.product().id())) {
                diff.add("low-stock threshold " + Objects.toString(plan.product().details().lowStockThreshold(),
                        "default") + " → " + threshold);
            }
            if (diff.isEmpty()) {
                unchanged++;
            } else {
                changes.add(new Change(plan.line(), plan.product().id(), plan.product().name(),
                        plan.variant() == null ? null : plan.variant().label(), diff));
            }
        }
        if (dryRun || (changes.isEmpty())) {
            return new ImportResult(false, changes, unchanged, List.of());
        }
        apply(plans, thresholds);
        events.publishEvent(new ProductChangedEvent());
        return new ImportResult(true, changes, unchanged, List.of());
    }

    private static List<CSVRecord> parse(String content) {
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true).setTrim(true).setIgnoreEmptyLines(true).build()
                .parse(new StringReader(content))) {
            Set<String> headers = new HashSet<>(parser.getHeaderNames().stream().map(String::toLowerCase).toList());
            if (!headers.contains("product_id") && !headers.contains("sku")) {
                throw ApiException.badRequest("file: the first line must be a header with product_id or sku "
                        + "(export the products first to get the right columns)");
            }
            return parser.getRecords();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw ApiException.badRequest("file: not a readable CSV (" + e.getMessage() + ")");
        } catch (UncheckedIOException e) {
            throw ApiException.badRequest("file: not a readable CSV (" + e.getCause().getMessage() + ")");
        }
    }

    private RowPlan plan(CSVRecord r, int line, Map<String, Product> byId, Map<String, String[]> bySku) {
        String productId = cell(r, "product_id");
        String variantId = cell(r, "variant_id");
        String sku = cell(r, "sku");
        Product product;
        Variant variant = null;
        if (productId != null) {
            product = byId.get(productId);
            if (product == null) {
                throw ApiException.badRequest("unknown product_id " + productId);
            }
            if (product.hasVariants()) {
                if (variantId == null && sku != null) {
                    String[] hit = bySku.get(sku);
                    variantId = hit != null && productId.equals(hit[0]) ? hit[1] : null;
                }
                if (variantId == null) {
                    throw ApiException.badRequest(product.name() + " has variants: give its variant_id or sku");
                }
                String wanted = variantId;
                variant = product.details().variants().find(wanted)
                        .orElseThrow(() -> ApiException.badRequest("unknown variant_id " + wanted));
            } else if (variantId != null) {
                throw ApiException.badRequest(product.name() + " has no variants, leave variant_id blank");
            }
        } else if (sku != null) {
            String[] hit = bySku.get(sku);
            if (hit == null) {
                throw ApiException.badRequest("unknown sku " + sku);
            }
            product = byId.get(hit[0]);
            variant = hit[1] == null ? null : product.details().variants().find(hit[1]).orElseThrow();
        } else {
            throw ApiException.badRequest("needs a product_id or sku");
        }

        Double price = decimal(r, "price", true);
        Double mrp = decimal(r, "mrp", false);
        Integer stock = integer(r, "stock", 0, VariantRules.MAX_STOCK);
        String status = cell(r, "status");
        if (status != null) {
            status = variant != null ? VariantStatus.parse(status).name() : ProductStatus.parse(status).name();
        }
        double newPrice = price != null ? price : variant != null ? variant.price() : product.price();
        Double newMrp = mrp != null ? mrp : variant != null ? variant.mrp() : product.details().mrp();
        if (newMrp != null && newMrp < newPrice) {
            throw ApiException.badRequest("mrp " + number(newMrp) + " is below the price " + number(newPrice));
        }
        return new RowPlan(line, product, variant, price, mrp, stock, status);
    }

    private static List<String> describe(RowPlan p) {
        List<String> diff = new ArrayList<>();
        double price = p.variant() != null ? p.variant().price() : p.product().price();
        Double mrp = p.variant() != null ? p.variant().mrp() : p.product().details().mrp();
        int stock = p.variant() != null ? p.variant().stock() : p.product().stock();
        String status = p.variant() != null ? p.variant().status().name() : p.product().details().status().name();
        if (p.price() != null && p.price() != price) {
            diff.add("price " + number(price) + " → " + number(p.price()));
        }
        if (p.mrp() != null && !p.mrp().equals(mrp)) {
            diff.add("MRP " + (mrp == null ? "none" : number(mrp)) + " → " + number(p.mrp()));
        }
        if (p.stock() != null && p.stock() != stock) {
            diff.add("stock " + stock + " → " + p.stock());
        }
        if (p.status() != null && !p.status().equals(status)) {
            diff.add("status " + status + " → " + p.status());
        }
        return diff;
    }

    /** Writes per product. Prices and stock go through the revision-checked write, so no sale is lost. */
    private void apply(List<RowPlan> plans, Map<String, Integer> thresholds) {
        Map<String, List<RowPlan>> byProduct = new LinkedHashMap<>();
        plans.forEach(p -> byProduct.computeIfAbsent(p.product().id(), k -> new ArrayList<>()).add(p));
        for (var entry : byProduct.entrySet()) {
            String id = entry.getKey();
            List<RowPlan> rows = entry.getValue();
            Integer threshold = thresholds.get(id);
            RowPlan simple = rows.get(0).variant() == null ? rows.get(0) : null;

            // Fields that are not price or stock, through the plain save.
            if (threshold != null || (simple != null && (simple.mrp() != null || simple.status() != null))) {
                Product fresh = products.findById(id).orElseThrow(() -> ApiException.conflict(
                        "A product was deleted during the import; nothing after it was written"));
                ProductDetails d = fresh.details();
                products.save(fresh.withDetails(new ProductDetails(d.slug(), d.sku(), d.description(), d.brand(),
                        simple != null && simple.mrp() != null ? simple.mrp() : d.mrp(), d.taxRate(), d.hsn(),
                        simple != null && simple.status() != null ? ProductStatus.parse(simple.status()) : d.status(),
                        d.tags(), d.images(), d.categoryId(), d.countryOfOrigin(), d.variants(),
                        threshold != null ? threshold : d.lowStockThreshold(), d.sizeChart())));
            }

            products.updateVariants(id, current -> {
                if (simple != null) {
                    return new Product(current.id(), current.name(), current.photo(),
                            simple.price() != null ? simple.price() : current.price(),
                            simple.stock() != null ? simple.stock() : current.stock(), current.category(),
                            current.createdAt(), current.details());
                }
                Map<String, RowPlan> byVariant = new HashMap<>();
                rows.forEach(r -> byVariant.put(r.variant().id(), r));
                ProductVariants v = current.details().variants();
                List<Variant> items = v.items().stream().map(item -> {
                    RowPlan r = byVariant.get(item.id());
                    if (r == null) {
                        return item;
                    }
                    double price = r.price() != null ? r.price() : item.price();
                    Double mrp = r.mrp() != null ? r.mrp() : item.mrp();
                    if (mrp != null && mrp < price) {
                        throw ApiException.conflict("Line " + r.line() + ": the price changed during the import");
                    }
                    return new Variant(item.id(), item.sku(), item.options(), price, mrp,
                            r.stock() != null ? r.stock() : item.stock(),
                            r.status() != null ? VariantStatus.parse(r.status()) : item.status());
                }).toList();
                return current.withVariants(new ProductVariants(v.axes(), items));
            }).orElseThrow(() -> ApiException.conflict(
                    "A product was deleted during the import; nothing after it was written"));
        }
    }

    private static String cell(CSVRecord r, String header) {
        if (!r.isMapped(header) || !r.isSet(header)) {
            return null;
        }
        String v = r.get(header).trim();
        // Undo the formula guard added on export.
        if (v.length() > 1 && v.charAt(0) == '\'' && "=+-@".indexOf(v.charAt(1)) >= 0) {
            v = v.substring(1);
        }
        return v.isEmpty() ? null : v;
    }

    private static Double decimal(CSVRecord r, String header, boolean positive) {
        String v = cell(r, header);
        if (v == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(v.replace(",", "").replace("₹", ""));
            if (Double.isNaN(d) || d < 0 || (positive && d == 0) || d > MAX_PRICE) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(header + " '" + v + "' must be a number" + (positive ? " above 0" : ""));
        }
    }

    private static Integer integer(CSVRecord r, String header, int min, int max) {
        String v = cell(r, header);
        if (v == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(header + " '" + v + "' must be a whole number from " + min + " to " + max);
        }
    }

    /** Spreadsheet apps run cells starting with = + - @ as formulas; a leading quote keeps them text. */
    private static String text(String value) {
        if (value == null) {
            return "";
        }
        return !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String number(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
