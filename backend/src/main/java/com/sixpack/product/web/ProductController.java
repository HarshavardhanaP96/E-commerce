package com.sixpack.product.web;

import com.sixpack.product.application.ProductCsvService;
import com.sixpack.product.application.ProductCsvService.ImportResult;
import com.sixpack.product.application.ProductService;
import com.sixpack.product.application.ProductService.ProductCommand;
import com.sixpack.product.domain.Product;
import com.sixpack.shared.PageResult;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/product")
class ProductController {

    private final ProductService service;
    private final ProductCsvService csv;

    ProductController(ProductService service, ProductCsvService csv) {
        this.service = service;
        this.csv = csv;
    }

    @GetMapping("/latest")
    Map<String, Object> latest() {
        return ApiResponse.of("products", views(service.latest()));
    }

    @GetMapping("/categories")
    Map<String, Object> categories() {
        return ApiResponse.of("categories", service.categories());
    }

    @GetMapping("/all")
    Map<String, Object> search(
            @RequestParam(required = false) @Size(max = 100) String search,
            @RequestParam(required = false) @PositiveOrZero Double price,
            @RequestParam(required = false) @Size(max = 50) String category,
            @RequestParam(required = false) @Pattern(regexp = "(?i)asc|dsc|desc|") String sort,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10_000) int page,
            @RequestParam(required = false) @Size(max = 3) List<@Size(max = 70) String> option) {
        PageResult<Product> result = service.search(search, price, category, sort, page, option);
        long totalPage = (long) Math.ceil(result.total() / (double) service.pageSize());
        return ApiResponse.of("products", views(result.items()), "totalPage", totalPage, "total", result.total());
    }

    @GetMapping("/options")
    Map<String, Object> options() {
        return ApiResponse.of("options", service.optionFacets().stream().map(OptionFacetView::from).toList());
    }

    /** Admin: sellable lines at or below their threshold, emptiest first. */
    @GetMapping("/low-stock")
    Map<String, Object> lowStock() {
        return ApiResponse.of("alerts", service.lowStock().stream().map(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("productId", a.productId());
            m.put("name", a.name());
            m.put("photo", a.photo());
            m.put("variantId", a.variantId());
            m.put("label", a.label());
            m.put("sku", a.sku());
            m.put("stock", a.stock());
            m.put("threshold", a.threshold());
            m.put("out", a.isOut());
            return m;
        }).toList());
    }

    @PutMapping(value = "/{id}/size-chart", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> replaceSizeChart(@PathVariable String id, @Valid @RequestBody SizeChartRequest request) {
        Product updated = service.replaceSizeChart(id, request.toCommand());
        return ApiResponse.of("message", "Size guide saved successfully", "product", view(updated));
    }

    /** Admin: every product without variants and every variant, for editing in a spreadsheet. */
    @GetMapping(value = "/export", produces = "text/csv")
    ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("products-" + LocalDate.now() + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.export());
    }

    /** Admin: updates price, MRP, stock, status and threshold from a CSV. Writes only with {@code dryRun=false}. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> importCsv(@RequestPart("file") MultipartFile file,
                                                  @RequestParam(defaultValue = "true") boolean dryRun) {
        ImportResult result = csv.importCsv(Uploads.bytes(file), dryRun);
        if (!result.problems().isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", result.problems().size() + " row(s) need fixing; nothing was changed");
            body.put("errors", result.problems());
            return ResponseEntity.badRequest().body(body);
        }
        return ResponseEntity.ok(ApiResponse.of("applied", result.applied(), "changes", result.changes(),
                "unchanged", result.unchanged()));
    }

    @GetMapping("/admin-products")
    Map<String, Object> adminProducts() {
        return ApiResponse.of("products", views(service.all()));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        return ApiResponse.of("product", view(service.get(id)));
    }

    @PostMapping(value = "/new", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(
            @RequestParam @NotBlank @Size(max = 120) String name,
            @RequestParam @Positive @DecimalMax("10000000") Double price,
            @RequestParam @PositiveOrZero @Max(1_000_000) Integer stock,
            @RequestParam @NotBlank @Size(max = 50) String category,
            @RequestPart("photo") MultipartFile photo,
            @Valid @ModelAttribute ProductDetailsForm details) {
        Product created = service.create(new ProductCommand(name, price, stock, category, Uploads.bytes(photo),
                details.toCommand()));
        return ApiResponse.of("message", "Product created successfully", "product", view(created));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> update(
            @PathVariable String id,
            @RequestParam(required = false) @Size(max = 120) String name,
            @RequestParam(required = false) @Positive @DecimalMax("10000000") Double price,
            @RequestParam(required = false) @PositiveOrZero @Max(1_000_000) Integer stock,
            @RequestParam(required = false) @Size(max = 50) String category,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @Valid @ModelAttribute ProductDetailsForm details) {
        Product updated = service.update(id, new ProductCommand(name, price, stock, category, Uploads.bytes(photo),
                details.toCommand()));
        return ApiResponse.of("message", "Product updated successfully", "product", view(updated));
    }

    @PutMapping(value = "/{id}/variants", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> replaceVariants(@PathVariable String id, @Valid @RequestBody VariantsRequest request) {
        Product updated = service.replaceVariants(id, request.toCommand());
        return ApiResponse.of("message", "Variants saved successfully", "product", view(updated));
    }

    @PatchMapping(value = "/{id}/variants/{variantId}/stock", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> adjustVariantStock(@PathVariable String id, @PathVariable String variantId,
                                           @Valid @RequestBody StockRequest request) {
        Product updated = service.adjustVariantStock(id, variantId, request.toCommand());
        return ApiResponse.of("message", "Stock updated successfully", "product", view(updated));
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.message("Product deleted successfully");
    }

    private ProductView view(Product p) {
        return ProductView.from(p, service.lowStockDefault());
    }

    private List<ProductView> views(List<Product> products) {
        return products.stream().map(this::view).toList();
    }

}
