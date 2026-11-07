package com.sixpack.order.web;

import com.sixpack.shared.geo.IndianStates;
import com.sixpack.shared.web.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed lists the checkout form offers. Public: shoppers see them before signing in. */
@RestController
@RequestMapping("/api/v1/reference")
class ReferenceController {

    @GetMapping("/indian-states")
    Map<String, Object> indianStates() {
        return ApiResponse.of("states", IndianStates.all());
    }
}
