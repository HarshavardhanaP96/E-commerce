package com.sixpack.product.web;

import com.sixpack.product.application.ProductService.StockCommand;

/** Body of {@code PATCH /product/{id}/variants/{variantId}/stock}: exactly one of {@code delta} or {@code set}. */
record StockRequest(Integer delta, Integer set) {

    StockCommand toCommand() {
        return new StockCommand(delta, set);
    }
}
