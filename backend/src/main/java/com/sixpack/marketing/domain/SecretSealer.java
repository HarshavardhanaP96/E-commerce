package com.sixpack.marketing.domain;

/**
 * Encrypts owner-set secrets (ADR-029). The associated data binds a ciphertext to one store, provider and field,
 * so a value copied to another row does not decrypt.
 */
public interface SecretSealer {

    /** False when the platform has no key: server-side features are then off (fail closed). */
    boolean available();

    SealedSecret seal(String plaintext, String aad);

    /** @throws UnreadableSecretException when the key is gone or the data or its binding does not match */
    String open(SealedSecret sealed, String aad);

    static String aad(String tenantId, Provider provider, String field) {
        return tenantId + "|" + provider.name() + "|" + field;
    }

    /** Carries no detail on purpose: nothing about the secret or the key may reach a log or a response. */
    final class UnreadableSecretException extends RuntimeException {
        public UnreadableSecretException() {
            super("Stored secret cannot be read", null, false, false);
        }
    }
}
