package com.sixpack.tenant.web;

import com.sixpack.shared.tenant.StoreType;
import com.sixpack.shared.web.ApiResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The store types the operator can onboard, with what each one pre-sets (ADR-030). */
@RestController
@RequestMapping("/api/v1/platform/store-types")
class PlatformStoreTypeController {

    @GetMapping
    Map<String, Object> list() {
        return ApiResponse.of("types", Arrays.stream(StoreType.values()).map(t -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("key", t.name());
            v.put("label", t.label());
            v.put("description", t.description());
            v.put("features", t.features());
            v.put("settings", t.settings());
            return v;
        }).toList());
    }
}
