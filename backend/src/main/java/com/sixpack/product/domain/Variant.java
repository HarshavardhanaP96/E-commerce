package com.sixpack.product.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A separately stocked, separately priced way to buy a product.
 *
 * @param id stable, server-generated; carts, payment intents and orders refer to it
 * @param mrp optional; falls back to the product's MRP when null
 */
public record Variant(String id, String sku, List<OptionChoice> options, double price, Double mrp, int stock,
                      VariantStatus status) {

    public Variant {
        options = options == null ? List.of() : List.copyOf(options);
        status = status == null ? VariantStatus.ACTIVE : status;
    }

    /** Human label such as "M / Black", in axis order. */
    public String label() {
        return options.stream().map(OptionChoice::value).collect(Collectors.joining(" / "));
    }

    public Variant withStock(int newStock) {
        return new Variant(id, sku, options, price, mrp, newStock, status);
    }
}
