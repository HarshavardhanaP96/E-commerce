package com.sixpack.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Salted SHA-256 for values we must be able to compare but never store in the clear: the IP kept as consent
 * evidence and in the audit log, and an attempted login email.
 */
@Component
public class EvidenceHasher {

    private final String salt;

    EvidenceHasher(@Value("${app.legal.ip-hash-salt:}") String salt) {
        this.salt = salt == null ? "" : salt;
    }

    /** @return 64 hex characters, or the empty string for a null or blank input */
    public String hash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((salt + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
