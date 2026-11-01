package com.sixpack.marketing.infra.crypto;

import com.sixpack.marketing.application.MarketingProperties;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** AES-256-GCM, random 12-byte IV, 128-bit tag (ADR-029). Seals with the newest key, opens with the one named. */
@Component
class SecretBox implements SecretSealer {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9]{1,16}$");
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKey> keys = new LinkedHashMap<>(); // newest first
    private final SecureRandom random = new SecureRandom();

    @Autowired
    SecretBox(MarketingProperties props) {
        this(props.keys());
    }

    SecretBox(String spec) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        String[] entries = spec.split(",");
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].strip();
            int colon = entry.indexOf(':');
            String id = colon < 0 ? "" : entry.substring(0, colon);
            byte[] raw = decode(colon < 0 ? "" : entry.substring(colon + 1));
            if (!KEY_ID.matcher(id).matches() || raw.length != 32 || keys.containsKey(id)) {
                // Never echo the entry: it is key material.
                throw new IllegalStateException("MARKETING_KEYS entry " + (i + 1)
                        + " must be <id>:<base64 of 32 bytes> with a unique id");
            }
            if (Arrays.equals(raw, new byte[32])) {
                log.warn("MARKETING_KEYS key '{}' is the all-zero example key: generate a real one "
                        + "(openssl rand -base64 32) for any shared environment", id);
            }
            keys.put(id, new SecretKeySpec(raw, "AES"));
        }
    }

    @Override
    public boolean available() {
        return !keys.isEmpty();
    }

    @Override
    public SealedSecret seal(String plaintext, String aad) {
        Map.Entry<String, SecretKey> newest = keys.entrySet().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No marketing key configured"));
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            byte[] sealed = cipher(Cipher.ENCRYPT_MODE, newest.getValue(), iv, aad)
                    .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new SealedSecret(newest.getKey(), b64(iv), b64(sealed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String open(SealedSecret sealed, String aad) {
        SecretKey key = sealed == null ? null : keys.get(sealed.keyId());
        if (key == null) {
            throw new UnreadableSecretException();
        }
        try {
            byte[] iv = Base64.getDecoder().decode(sealed.iv());
            byte[] plain = cipher(Cipher.DECRYPT_MODE, key, iv, aad)
                    .doFinal(Base64.getDecoder().decode(sealed.ciphertext()));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new UnreadableSecretException();
        }
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] iv, String aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
