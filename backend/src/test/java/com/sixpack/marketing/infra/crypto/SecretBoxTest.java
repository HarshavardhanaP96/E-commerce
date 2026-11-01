package com.sixpack.marketing.infra.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.marketing.domain.Provider;
import com.sixpack.marketing.domain.SealedSecret;
import com.sixpack.marketing.domain.SecretSealer;
import com.sixpack.marketing.domain.SecretSealer.UnreadableSecretException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static final String SECRET = "fake-meta-token-aaaaaaaa";
    private static final String AAD = SecretSealer.aad("t_a", Provider.META, "server.secret");

    /** {@code id:<base64 of 32 bytes all equal to fill>}, built here so no key literal sits in the repo. */
    private static String key(String id, int fill) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) fill);
        return id + ":" + Base64.getEncoder().encodeToString(raw);
    }

    private final SecretBox box = new SecretBox(key("k1", 7));

    @Test
    void opensWhatItSealed() {
        assertThat(box.open(box.seal(SECRET, AAD), AAD)).isEqualTo(SECRET);
    }

    @Test
    void theSameSecretSealsDifferentlyEachTimeWithATwelveByteIv() {
        SealedSecret a = box.seal(SECRET, AAD);
        SealedSecret b = box.seal(SECRET, AAD);
        assertThat(a.keyId()).isEqualTo("k1");
        assertThat(Base64.getDecoder().decode(a.iv())).hasSize(12);
        assertThat(a.iv()).isNotEqualTo(b.iv());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext()).doesNotContain(SECRET);
    }

    @Test
    void anotherStoresOrProvidersBindingCannotOpenIt() {
        SealedSecret sealed = box.seal(SECRET, AAD);
        for (String other : List.of(SecretSealer.aad("t_b", Provider.META, "server.secret"),
                SecretSealer.aad("t_a", Provider.GOOGLE, "server.secret"),
                SecretSealer.aad("t_a", Provider.META, "oauth.refreshToken"))) {
            assertThatThrownBy(() -> box.open(sealed, other)).isInstanceOf(UnreadableSecretException.class);
        }
    }

    @Test
    void tamperedCiphertextTagOrIvIsRefused() {
        SealedSecret sealed = box.seal(SECRET, AAD);
        byte[] data = Base64.getDecoder().decode(sealed.ciphertext());
        byte[] iv = Base64.getDecoder().decode(sealed.iv());
        for (SealedSecret bad : List.of(
                new SealedSecret("k1", sealed.iv(), flip(data, 0)),               // ciphertext
                new SealedSecret("k1", sealed.iv(), flip(data, data.length - 1)), // tag (GCM appends it)
                new SealedSecret("k1", Base64.getEncoder().encodeToString(Base64.getDecoder().decode(flip(iv, 3))),
                        sealed.ciphertext()))) {
            assertThatThrownBy(() -> box.open(bad, AAD)).isInstanceOf(UnreadableSecretException.class);
        }
    }

    @Test
    void anOlderKeyStillOpensAfterANewOneIsPutFirst() {
        SealedSecret old = box.seal(SECRET, AAD);
        SecretBox rotated = new SecretBox(key("k2", 9) + "," + key("k1", 7));
        assertThat(rotated.open(old, AAD)).isEqualTo(SECRET);
        assertThat(rotated.seal(SECRET, AAD).keyId()).isEqualTo("k2");
    }

    @Test
    void aSecretSealedWithARemovedKeyIsUnreadable() {
        SealedSecret old = box.seal(SECRET, AAD);
        assertThatThrownBy(() -> new SecretBox(key("k2", 9)).open(old, AAD))
                .isInstanceOf(UnreadableSecretException.class).hasMessageNotContaining(SECRET);
    }

    @Test
    void withoutKeysNothingIsAvailableAndSealingFails() {
        for (SecretBox empty : List.of(new SecretBox(""), new SecretBox((String) null), new SecretBox("  "))) {
            assertThat(empty.available()).isFalse();
            assertThatThrownBy(() -> empty.seal(SECRET, AAD)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(box.available()).isTrue();
    }

    @Test
    void aMalformedKeyListFailsWithoutEchoingTheKeyMaterial() {
        String good = key("k1", 7);
        String material = good.substring(3);
        for (String spec : List.of("k1", "k1:" + material.substring(0, 20), "bad id!:" + material, good + "," + good,
                ":" + material)) {
            assertThatThrownBy(() -> new SecretBox(spec)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MARKETING_KEYS").hasMessageNotContaining(material);
        }
    }

    private static String flip(byte[] bytes, int index) {
        byte[] copy = bytes.clone();
        copy[index] ^= 1;
        return Base64.getEncoder().encodeToString(copy);
    }
}
