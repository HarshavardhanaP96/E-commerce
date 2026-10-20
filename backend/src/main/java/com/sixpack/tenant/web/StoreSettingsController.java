package com.sixpack.tenant.web;

import com.sixpack.shared.web.ApiResponse;
import com.sixpack.tenant.application.StoreSettingsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The store admin's own shop rules (shipping, GST, coupons, catalogue, invoice, time zone) and storefront details. */
@RestController
@RequestMapping("/api/v1/store/settings")
class StoreSettingsController {

    private final StoreSettingsService service;

    StoreSettingsController(StoreSettingsService service) {
        this.service = service;
    }

    @GetMapping
    Map<String, Object> get() {
        return body(service.get());
    }

    @PutMapping
    Map<String, Object> update(@RequestBody StoreSettingsService.Command command) {
        return body(service.update(command));
    }

    private static Map<String, Object> body(StoreSettingsService.View v) {
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put("commerce", v.settings().commerce());
        settings.put("catalog", v.settings().catalog());
        settings.put("invoice", v.settings().invoice());
        settings.put("regional", v.settings().regional());
        settings.put("storefront", v.storefront());
        return ApiResponse.of("settings", settings, "effective", v.effective(), "defaults", v.defaults());
    }
}
