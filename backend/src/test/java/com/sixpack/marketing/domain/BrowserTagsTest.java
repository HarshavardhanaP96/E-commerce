package com.sixpack.marketing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BrowserTagsTest {

    @Test
    void acceptsWellFormedIds() {
        BrowserTags t = new BrowserTags("G-AB12CD3", "AW-123456789", "Purchase_label-1", "GTM-ABC123",
                "123456789012345");

        assertThat(t.ga4MeasurementId()).isEqualTo("G-AB12CD3");
        assertThat(t.adsConversionId()).isEqualTo("AW-123456789");
        assertThat(t.adsPurchaseLabel()).isEqualTo("Purchase_label-1");
        assertThat(t.gtmContainerId()).isEqualTo("GTM-ABC123");
        assertThat(t.pixelId()).isEqualTo("123456789012345");
        assertThat(t.isEmpty()).isFalse();
    }

    @Test
    void acceptsTheShortestAndLongestForms() {
        new BrowserTags("G-ABCD", "AW-123456", "a", "GTM-ABCD", "1234567890");
        new BrowserTags("G-ABCDEFGHIJ12", "AW-123456789012", "a".repeat(40), "GTM-ABCDEFGHIJ", "1".repeat(20));
    }

    @Test
    void trimsValuesAndTurnsBlankIntoNull() {
        BrowserTags t = new BrowserTags("  G-AB12CD3\t", "", "   ", null, " 1234567890 ");

        assertThat(t).isEqualTo(new BrowserTags("G-AB12CD3", null, null, null, "1234567890"));
    }

    @Test
    void allBlankIsEmpty() {
        assertThat(new BrowserTags(" ", "", null, "\n", null).isEmpty()).isTrue();
        assertThat(BrowserTags.NONE.isEmpty()).isTrue();
    }

    static Stream<Arguments> malformed() {
        String ga4 = "ga4MeasurementId: must look like G-XXXXXXX";
        String ads = "adsConversionId: must look like AW-123456789";
        String label = "adsPurchaseLabel: use 1-40 letters, digits, _ or -";
        String gtm = "gtmContainerId: must look like GTM-XXXXXX";
        String pixel = "pixelId: must be 10-20 digits";
        return Stream.of(
                Arguments.of("ga4MeasurementId", "G-x\"><script>alert(1)</script>", ga4),
                Arguments.of("ga4MeasurementId", "G-AB12CD3');alert(1);//", ga4),
                Arguments.of("ga4MeasurementId", "G-AB12CD3\n<script>", ga4),
                Arguments.of("ga4MeasurementId", "g-ab12cd3", ga4),
                Arguments.of("ga4MeasurementId", "G-ABC", ga4),
                Arguments.of("ga4MeasurementId", "G-ABCDEFGHIJ123", ga4),
                Arguments.of("ga4MeasurementId", "UA-12345-1", ga4),
                Arguments.of("adsConversionId", "AW-12345", ads),
                Arguments.of("adsConversionId", "AW-1234567890123", ads),
                Arguments.of("adsConversionId", "AW-123456789\" onload=\"x", ads),
                Arguments.of("adsConversionId", "123456789", ads),
                Arguments.of("adsPurchaseLabel", "has space", label),
                Arguments.of("adsPurchaseLabel", "a".repeat(41), label),
                Arguments.of("adsPurchaseLabel", "<b>", label),
                Arguments.of("gtmContainerId", "GTM-abc123", gtm),
                Arguments.of("gtmContainerId", "GTM-ABC", gtm),
                Arguments.of("gtmContainerId", "GTM-ABC123</script>", gtm),
                Arguments.of("pixelId", "1234567890123456789012345", pixel),
                Arguments.of("pixelId", "123456789", pixel),
                Arguments.of("pixelId", "1234567890 OR 1=1", pixel),
                // Arabic-Indic digits: \d must mean ASCII 0-9 only
                Arguments.of("pixelId", "١٢٣٤٥٦٧٨٩٠", pixel));
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void rejectsMalformedIdsNamingTheField(String field, String value, String message) {
        assertThatThrownBy(() -> with(field, value))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    @Test
    void aPurchaseLabelNeedsAConversionId() {
        assertThatThrownBy(() -> new BrowserTags(null, null, "purchase", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("adsPurchaseLabel: needs adsConversionId");
        assertThatThrownBy(() -> new BrowserTags(null, "  ", "purchase", null, null))
                .hasMessage("adsPurchaseLabel: needs adsConversionId");
    }

    @Test
    void forAProviderOnlyItsOwnFieldsAreKeptAndTheOthersAreNotChecked() {
        BrowserTags meta = BrowserTags.of(Provider.META, "junk", "junk", "junk", "junk", "1234567890");
        assertThat(meta).isEqualTo(new BrowserTags(null, null, null, null, "1234567890"));

        BrowserTags google = BrowserTags.of(Provider.GOOGLE, "G-AB12CD3", null, null, null, "<script>");
        assertThat(google).isEqualTo(new BrowserTags("G-AB12CD3", null, null, null, null));
    }

    private static BrowserTags with(String field, String value) {
        return switch (field) {
            case "ga4MeasurementId" -> new BrowserTags(value, null, null, null, null);
            case "adsConversionId" -> new BrowserTags(null, value, null, null, null);
            case "adsPurchaseLabel" -> new BrowserTags(null, "AW-123456789", value, null, null);
            case "gtmContainerId" -> new BrowserTags(null, null, null, value, null);
            case "pixelId" -> new BrowserTags(null, null, null, null, value);
            default -> throw new AssertionError("unknown field " + field);
        };
    }
}
