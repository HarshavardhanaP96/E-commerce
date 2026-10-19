package com.sixpack.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Salted SHA-256 for values we must be able to compare but never store in the clear: the IP kept as consent
 * evidence and in the audit log, and an attempted login email.
 */
@Component
public class EvidenceHasher {

    private static final Logger log = LoggerFactory.getLogger(EvidenceHasher.class);

    private final String salt;

    EvidenceHasher(@Value("${app.legal.ip-hash-salt:}") String salt) {
        this.salt = salt == null ? "" : salt;
        if (this.salt.isBlank()) {
            log.warn("CONSENT_IP_SALT is not set: IP and attempted-login-email hashes stored for consent evidence "
                    + "and the audit log are unsalted. Set it in every real environment.");
        }
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
