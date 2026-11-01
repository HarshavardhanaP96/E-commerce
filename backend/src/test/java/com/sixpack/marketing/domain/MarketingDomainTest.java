package com.sixpack.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MarketingDomainTest {

    private static final BrowserTags PIXEL = new BrowserTags(null, null, null, null, "1234567890");
    private static final SealedSecret SEALED = new SealedSecret("k1", "aXY=", "Y2lwaGVy");

    @Test
    void aFailingServerConnectionShowsAsErrorUntilASendSucceeds() {
        Instant t0 = Instant.parse("2026-10-31T10:00:00Z");
        ServerSettings on = new ServerSettings(true, null, SEALED);
        LastError error = new LastError(t0, "AUTH_FAILED", "Meta rejected the saved secret.");

        assertThat(new Integration(Provider.META, PIXEL, on, error, null, t0).status())
                .isEqualTo(Integration.Status.ERROR);
        assertThat(new Integration(Provider.META, PIXEL, on, error, t0.minusSeconds(60), t0).status())
                .isEqualTo(Integration.Status.ERROR);
        assertThat(new Integration(Provider.META, PIXEL, on, error, t0.plusSeconds(60), t0).status())
                .isEqualTo(Integration.Status.ACTIVE);
        assertThat(new Integration(Provider.META, PIXEL, ServerSettings.OFF, error, null, t0).status())
                .isEqualTo(Integration.Status.ACTIVE); // server events off: the browser tag still works
        assertThat(new Integration(Provider.META, BrowserTags.NONE, on, error, null, t0).status())
                .isEqualTo(Integration.Status.NOT_CONNECTED);
    }

    @Test
    void theServerIdIsThePixelForMetaAndTheMeasurementIdForGoogle() {
        assertThat(new Integration(Provider.META, PIXEL, Instant.EPOCH).serverId()).isEqualTo("1234567890");
        assertThat(Integration.serverId(Provider.GOOGLE, new BrowserTags("G-AB12CD3", null, null, null, null)))
                .isEqualTo("G-AB12CD3");
        assertThat(Integration.serverId(Provider.GOOGLE, PIXEL)).isNull();
        assertThat(Integration.notConnected(Provider.META).server()).isEqualTo(ServerSettings.OFF);
    }

    @Test
    void serverSettingsCheckTheTestCodeAndTheSecretShape() {
        assertThat(new ServerSettings(false, "  TEST123 ", null).testEventCode()).isEqualTo("TEST123");
        assertThat(new ServerSettings(false, " ", null).testEventCode()).isNull();
        assertThatThrownBy(() -> new ServerSettings(false, "TE ST", null))
                .hasMessage("server.testEventCode: use 1-32 letters or digits");
        assertThat(ServerSettings.checkSecret(" fake-meta-token-aaaaaaaa ")).isEqualTo("fake-meta-token-aaaaaaaa");
        for (String bad : new String[] {"short", "has space in the middle!", "x".repeat(513), ""}) {
            assertThatThrownBy(() -> ServerSettings.checkSecret(bad))
                    .hasMessage("server.secret: use 16-512 letters, digits, _ or -");
        }
        assertThat(new ServerSettings(true, null, SEALED).hasSecret()).isTrue();
    }

    @Test
    void deliveryCodesSayWhatHappenedAndWhichAreRetried() {
        assertThat(java.util.Arrays.stream(DeliveryCode.values()).filter(DeliveryCode::retryable))
                .containsExactlyInAnyOrder(DeliveryCode.TIMEOUT, DeliveryCode.RATE_LIMITED, DeliveryCode.PROVIDER_DOWN);
        assertThat(DeliveryCode.AUTH_FAILED.message(Provider.META))
                .isEqualTo("Meta rejected the saved secret. Create a new one and save it here.");
        assertThat(DeliveryCode.TOO_OLD.message(Provider.GOOGLE)).contains("Google");
    }

    @Test
    void aDeliveryFailureCarriesOnlyItsCodeAsMessage() {
        DeliveryException e = new DeliveryException(DeliveryCode.REJECTED, "Invalid parameter", Duration.ofSeconds(5));
        assertThat(e.getMessage()).isEqualTo("REJECTED");
        assertThat(e.getStackTrace()).isEmpty();
        assertThat(e.ownerMessage(Provider.META)).isEqualTo("Meta rejected the event: Invalid parameter");
        assertThat(new DeliveryException(DeliveryCode.TIMEOUT, null, null).ownerMessage(Provider.GOOGLE))
                .isEqualTo("Google did not answer in time. We will try again.");
    }

    @Test
    void credentialsNeverPrintTheSecret() {
        MarketingDestination.Credentials c = new MarketingDestination.Credentials("1234567890", "fake-meta-token-aaaaaaaa");
        assertThat(c.toString()).contains("1234567890").doesNotContain("fake-meta-token");
    }

    @Test
    void providersUseLowerCaseKeysWithGoogleFirst() {
        assertThat(Provider.values()).containsExactly(Provider.GOOGLE, Provider.META);
        assertThat(Provider.GOOGLE.key()).isEqualTo("google");
        assertThat(Provider.fromKey("google")).contains(Provider.GOOGLE);
        assertThat(Provider.fromKey("meta")).contains(Provider.META);
        assertThat(Provider.fromKey("GOOGLE")).isEmpty();
        assertThat(Provider.fromKey("tiktok")).isEmpty();
        assertThat(Provider.fromKey(null)).isEmpty();
    }

    @Test
    void anIntegrationWithIdsIsActiveAndWithoutIdsIsNotConnected() {
        Integration none = Integration.notConnected(Provider.META);
        assertThat(none.status()).isEqualTo(Integration.Status.NOT_CONNECTED);
        assertThat(none.browser()).isEqualTo(BrowserTags.NONE);
        assertThat(none.updatedAt()).isNull();

        Integration pixel = new Integration(Provider.META, new BrowserTags(null, null, null, null, "1234567890"),
                Instant.EPOCH);
        assertThat(pixel.status()).isEqualTo(Integration.Status.ACTIVE);
    }
}
