package com.sixpack.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the `{success: true, ...}` envelope used by every endpoint (see docs/foundations/contracts.md). */
public final class ApiResponse {

    private ApiResponse() {
    }

    public static Map<String, Object> message(String message) {
        return of("message", message);
    }

    public static Map<String, Object> of(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put(key, value);
        return body;
    }

    public static Map<String, Object> of(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> body = of(k1, v1);
        body.put(k2, v2);
        return body;
    }

    public static Map<String, Object> of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> body = of(k1, v1, k2, v2);
        body.put(k3, v3);
        return body;
    }

    public static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
