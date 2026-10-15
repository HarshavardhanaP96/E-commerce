package com.sixpack.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacTest {

    @Test
    void matchesRazorpaySignatureScheme() {
        // echo -n "order_123|pay_456" | openssl dgst -sha256 -hmac "secret"
        assertThat(Hmac.sha256Hex("secret", "order_123|pay_456"))
                .isEqualTo("18bfc0baafae8f6367711ee362f2201aaa3654274683100e5367bb9a2bd29cbe");
    }

    @Test
    void constantTimeEquals() {
        String sig = Hmac.sha256Hex("secret", "order_123|pay_456");
        assertThat(Hmac.constantTimeEquals(sig, Hmac.sha256Hex("secret", "order_123|pay_456"))).isTrue();
        assertThat(Hmac.constantTimeEquals(sig, Hmac.sha256Hex("other", "order_123|pay_456"))).isFalse();
        assertThat(Hmac.constantTimeEquals(sig, null)).isFalse();
    }
}
