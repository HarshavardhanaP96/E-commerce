package com.sixpack.shared.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RootController {

    @GetMapping("/")
    String root() {
        return "6Pack API is running at /api/v1";
    }
}
