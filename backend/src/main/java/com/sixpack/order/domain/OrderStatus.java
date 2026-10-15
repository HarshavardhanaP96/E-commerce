package com.sixpack.order.domain;

public enum OrderStatus {
    PROCESSING("Processing"), SHIPPED("Shipped"), DELIVERED("Delivered");

    private final String wire;

    OrderStatus(String wire) {
        this.wire = wire;
    }

    public String wireValue() {
        return wire;
    }

    public OrderStatus next() {
        return switch (this) {
            case PROCESSING -> SHIPPED;
            case SHIPPED, DELIVERED -> DELIVERED;
        };
    }

    public static OrderStatus fromWire(String value) {
        for (OrderStatus s : values()) {
            if (s.wire.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown order status: " + value);
    }
}
