import { describe, expect, it } from "vitest";
import { PROVIDERS, draftFrom, fieldError, toBrowser, validate } from "./marketingForm";

const google = PROVIDERS.find((p) => p.provider === "google")!.fields;
const meta = PROVIDERS.find((p) => p.provider === "meta")!.fields;
const blank = draftFrom(undefined, google);

describe("marketing form", () => {
  it("lists Google first, then Meta, with the fields the API expects", () => {
    expect(PROVIDERS.map((p) => p.provider)).toEqual(["google", "meta"]);
    expect(google.map((f) => f.key)).toEqual(["ga4MeasurementId", "adsConversionId", "adsPurchaseLabel", "gtmContainerId"]);
    expect(meta.map((f) => f.key)).toEqual(["pixelId"]);
  });

  it("accepts the shapes the server accepts, and empty fields", () => {
    expect(validate({ ga4MeasurementId: "G-AB12CD3", adsConversionId: "AW-123456789", adsPurchaseLabel: "AbC-1_x",
      gtmContainerId: "GTM-ABC123" }, google)).toEqual({});
    expect(validate(blank, google)).toEqual({});
    expect(validate({ pixelId: " 123456789012345 " }, meta)).toEqual({});
  });

  it.each([
    ["ga4MeasurementId", "g-ab12cd3"],
    ["ga4MeasurementId", 'G-AB12"><script>'],
    ["adsConversionId", "AW-12345"],
    ["gtmContainerId", "GTM-abc"],
  ])("rejects %s = %s with a message for that field", (key, value) => {
    expect(validate({ ...blank, [key]: value }, google)[key]).toMatch(/.+/);
  });

  it("rejects a pixel ID that is not 10–20 digits", () => {
    expect(validate({ pixelId: "123456789012345678901" }, meta).pixelId).toMatch(/digits/);
  });

  it("needs the Ads conversion ID before a purchase label", () => {
    expect(validate({ ...blank, adsPurchaseLabel: "AbC-1" }, google).adsPurchaseLabel).toMatch(/conversion ID/);
  });

  it("fills from the saved card and sends trimmed values, empty as null", () => {
    const draft = draftFrom({ provider: "meta", status: "ACTIVE", browser: { pixelId: "123456789012345" }, updatedAt: null }, meta);
    expect(draft).toEqual({ pixelId: "123456789012345" });
    expect(toBrowser({ ...blank, ga4MeasurementId: " G-AB12CD3 " }, google)).toEqual({
      ga4MeasurementId: "G-AB12CD3", adsConversionId: null, adsPurchaseLabel: null, gtmContainerId: null,
    });
  });

  it("reads the server's `<field>: <reason>` message as an error on that field", () => {
    expect(fieldError("ga4MeasurementId: must look like G-XXXXXXX", google))
      .toEqual({ key: "ga4MeasurementId", text: "must look like G-XXXXXXX" });
    expect(fieldError("browser.adsPurchaseLabel: requires adsConversionId", google))
      .toEqual({ key: "adsPurchaseLabel", text: "requires adsConversionId" });
    expect(fieldError("Something went wrong", google)).toEqual({ text: "Something went wrong" });
    expect(fieldError("pixelId: bad", google)).toEqual({ text: "pixelId: bad" });
  });
});
