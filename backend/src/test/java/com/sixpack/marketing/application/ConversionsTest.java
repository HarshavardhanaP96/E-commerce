package com.sixpack.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.sixpack.marketing.domain.Conversion;
import com.sixpack.marketing.domain.Provider;
import com.sixpack.order.OrderAttribution;
import com.sixpack.order.PaidOrders.Line;
import com.sixpack.order.PaidOrders.PaidOrder;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversionsTest {

    private static final Instant AT = Instant.parse("2026-10-31T10:00:00Z");

    private static PaidOrder order(String consentVersion, boolean marketing, String currency) {
        OrderAttribution a = new OrderAttribution(null, null, null, null, null, null, null,
                "fb.1.1730000000000.1098115397", null, "1234567890.1730000000", "/", "Mozilla/5.0 test",
                "https://shop.acme.test/shipping", new OrderAttribution.Consent(true, marketing, consentVersion, AT));
        return new PaidOrder("o1", "ORD-1", "u1", AT, 790, 90, 200, currency, "DIWALI",
                List.of(new Line("p1", "Lamp", "Red", 500, 1)), "Mary@Example.com", "98765 43210", a);
    }

    @Test
    void metaGetsHashedContactDetailsWhenTheShopperWasToldAboutIt() {
        Conversion c = Conversions.of(order("2026-10-31", true, "INR"), Provider.META, "USD", "2026-10-31", "91");
        assertThat(c.eventId()).isEqualTo("o1");
        assertThat(c.at()).isEqualTo(AT);
        assertThat(c.currency()).isEqualTo("INR");
        assertThat(c.value()).isEqualTo(790);
        assertThat(c.items()).containsExactly(new Conversion.Item("p1", "Lamp", "Red", 500, 1));
        assertThat(c.identity().emailSha256())
                .isEqualTo("f1904cf1a9d73a55fa5de0ac823c4403ded71afd4c3248d00bdcd0866552bb79");
        assertThat(c.identity().phoneSha256())
                .isEqualTo("92b5072176e723878b5e06ff3ca61898e4eb74e8c46642a0f2db800b17364ab0");
        assertThat(c.identity().externalIdSha256()).isEqualTo(PiiHasher.sha256("u1"));
        assertThat(c.identity().fbp()).isEqualTo("fb.1.1730000000000.1098115397");
        assertThat(c.identity().adConsent()).isTrue();
    }

    @Test
    void anOlderConsentSendsNoContactDetails() {
        Conversion c = Conversions.of(order("2026-10-30", true, "INR"), Provider.META, "INR", "2026-10-31", "91");
        assertThat(c.identity().emailSha256()).isNull();
        assertThat(c.identity().phoneSha256()).isNull();
        assertThat(c.identity().externalIdSha256()).isNull();
        assertThat(c.identity().fbp()).isEqualTo("fb.1.1730000000000.1098115397"); // already covered by the old text
    }

    @Test
    void googleNeverGetsContactDetailsButGetsItsClientId() {
        Conversion c = Conversions.of(order("2026-10-31", true, "INR"), Provider.GOOGLE, "INR", "2026-10-31", "91");
        assertThat(c.identity().emailSha256()).isNull();
        assertThat(c.identity().phoneSha256()).isNull();
        assertThat(c.identity().gaClientId()).isEqualTo("1234567890.1730000000");
    }

    @Test
    void theStoreCurrencyIsTheFallback() {
        assertThat(Conversions.of(order("2026-10-31", false, null), Provider.GOOGLE, "AED", "2026-10-31", "91")
                .currency()).isEqualTo("AED");
    }
}
