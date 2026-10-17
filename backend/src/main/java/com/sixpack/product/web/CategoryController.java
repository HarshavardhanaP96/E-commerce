package com.sixpack.product.web;

import com.sixpack.product.application.CategoryService;
import com.sixpack.product.application.CategoryService.CategoryCommand;
import com.sixpack.shared.web.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/category")
class CategoryController {

    private final CategoryService service;

    CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    Map<String, Object> list() {
        return ApiResponse.of("categories", service.all().stream().map(CategoryView::from).toList());
    }

    @PostMapping(value = "/new", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(
            @RequestParam @NotBlank @Size(max = 80) String name,
            @RequestParam(required = false) @Size(max = 64) String parentId,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        var created = service.create(new CategoryCommand(name, parentId, Uploads.bytes(image)));
        return ApiResponse.of("message", "Category created successfully", "category", CategoryView.from(created));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> update(
            @PathVariable String id,
            @RequestParam(required = false) @Size(max = 80) String name,
            @RequestParam(required = false) @Size(max = 64) String parentId,
            @RequestPart(value = "image", required = false) MultipartFile image) {
        var updated = service.update(id, new CategoryCommand(name, parentId, Uploads.bytes(image)));
        return ApiResponse.of("message", "Category updated successfully", "category", CategoryView.from(updated));
    }

    @DeleteMapping("/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.message("Category deleted successfully");
    }
}
