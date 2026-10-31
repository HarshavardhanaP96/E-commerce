import { describe, expect, it } from "vitest";
import { DELIVERY_LABEL, SERVER, serverDraftFrom, serverFieldError, toServerChange, validateServer } from "./serverEventsForm";

const SECRET = "fake-meta-token-aaaaaaaa";

describe("serverEventsForm", () => {
  it("starts from the saved settings and never pre-fills the secret", () => {
    expect(serverDraftFrom(undefined)).toEqual({ enabled: false, secret: "", testEventCode: "" });
    expect(serverDraftFrom({ provider: "meta", status: "ACTIVE", browser: {}, updatedAt: null,
      server: { enabled: true, hasSecret: true, testEventCode: "TEST123", available: true } }))
      .toEqual({ enabled: true, secret: "", testEventCode: "TEST123" });
  });

  it("mirrors the server's checks", () => {
    expect(validateServer({ enabled: false, secret: "short", testEventCode: "" }, SERVER.meta, false).secret).toMatch(/16–512/);
    expect(validateServer({ enabled: true, secret: "", testEventCode: "" }, SERVER.meta, false).secret).toMatch(/Paste the secret/);
    expect(validateServer({ enabled: true, secret: "", testEventCode: "" }, SERVER.meta, true)).toEqual({});
    expect(validateServer({ enabled: false, secret: "", testEventCode: "TE ST" }, SERVER.meta, true).testEventCode).toBeDefined();
    expect(validateServer({ enabled: false, secret: "", testEventCode: "TE ST" }, SERVER.google, true)).toEqual({});
  });

  it("sends the secret only when typed and the test code only for Meta", () => {
    expect(toServerChange({ enabled: true, secret: ` ${SECRET} `, testEventCode: " TEST123 " }, SERVER.meta))
      .toEqual({ enabled: true, secret: SECRET, testEventCode: "TEST123" });
    expect(toServerChange({ enabled: false, secret: "", testEventCode: "" }, SERVER.meta)).toEqual({ enabled: false, testEventCode: null });
    expect(toServerChange({ enabled: true, secret: "", testEventCode: "X" }, SERVER.google)).toEqual({ enabled: true });
  });

  it("maps the server's messages to a field or the top of the section", () => {
    expect(serverFieldError("server.secret: use 16-512 letters, digits, _ or -"))
      .toEqual({ key: "secret", text: "use 16-512 letters, digits, _ or -" });
    expect(serverFieldError("server.testEventCode: use 1-32 letters or digits").key).toBe("testEventCode");
    expect(serverFieldError("server: save the Meta Pixel ID first")).toEqual({ text: "Save the Meta Pixel ID first" });
    expect(serverFieldError("Try again later")).toEqual({ text: "Try again later" });
  });

  it("labels every delivery status", () => {
    expect(Object.keys(DELIVERY_LABEL).sort()).toEqual(["FAILED", "PENDING", "SENDING", "SENT", "SKIPPED"]);
  });
});
