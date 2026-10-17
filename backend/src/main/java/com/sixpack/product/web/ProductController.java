package com.sixpack.product.web;

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
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    ProductController(ProductService service) {
        this.service = service;
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
            @RequestParam(defaultValue = "1") @Min(1) @Max(10_000) int page) {
        PageResult<Product> result = service.search(search, price, category, sort, page);
        long totalPage = (long) Math.ceil(result.total() / (double) service.pageSize());
        return ApiResponse.of("products", views(result.items()), "totalPage", totalPage, "total", result.total());
    }

    @GetMapping("/admin-products")
    Map<String, Object> adminProducts() {
        return ApiResponse.of("products", views(service.all()));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        return ApiResponse.of("product", ProductView.from(service.get(id)));
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
        return ApiResponse.of("message", "Product created successfully", "product", ProductView.from(created));
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
        return ApiResponse.of("message", "Product updated successfully", "product", ProductView.from(updated));
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.message("Product deleted successfully");
    }

    private static List<ProductView> views(List<Product> products) {
        return products.stream().map(ProductView::from).toList();
    }

}
