package com.sixpack.user;

/** Published after a user account is deleted, so other modules can erase or anonymise what they hold. */
public record UserDeletedEvent(String userId) {
}
