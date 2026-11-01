package com.sixpack.marketing.domain;

/** An encrypted secret as stored: which key sealed it, the 12-byte IV and the ciphertext with its GCM tag (base64). */
public record SealedSecret(String keyId, String iv, String ciphertext) {
}
