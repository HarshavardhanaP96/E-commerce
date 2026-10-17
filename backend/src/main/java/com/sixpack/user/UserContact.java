package com.sixpack.user;

/** Contact details other modules may snapshot (for example onto an order). */
public record UserContact(String id, String name, String email, String phone) {
}
