// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { copyText } from "./clipboard";

const URL_TEXT = "https://shop.test/api/v1/feeds/abc/products.xml";
const setClipboard = (value: unknown) => Object.defineProperty(navigator, "clipboard", { value, configurable: true });
const setExecCommand = (fn: (command: string) => boolean) =>
  Object.defineProperty(document, "execCommand", { value: fn, configurable: true, writable: true });

afterEach(() => {
  Reflect.deleteProperty(navigator, "clipboard");
  Reflect.deleteProperty(document, "execCommand");
});

describe("copyText", () => {
  it("uses the Clipboard API when the browser offers it", async () => {
    const writeText = vi.fn(async () => undefined);
    setClipboard({ writeText });
    expect(await copyText(URL_TEXT)).toBe(true);
    expect(writeText).toHaveBeenCalledWith(URL_TEXT);
  });

  it("falls back to a hidden text box when there is no Clipboard API", async () => {
    setClipboard(undefined);
    let copied: string | undefined;
    setExecCommand((command) => {
      copied = command === "copy" ? document.querySelector("textarea")?.value : undefined;
      return true;
    });
    expect(await copyText(URL_TEXT)).toBe(true);
    expect(copied).toBe(URL_TEXT);
    expect(document.querySelector("textarea")).toBeNull();
  });

  it("falls back when the browser refuses the Clipboard API", async () => {
    setClipboard({ writeText: vi.fn(async () => { throw new Error("NotAllowedError"); }) });
    const exec = vi.fn(() => true);
    setExecCommand(exec);
    expect(await copyText(URL_TEXT)).toBe(true);
    expect(exec).toHaveBeenCalledWith("copy");
  });

  it("reports failure when nothing can copy", async () => {
    setClipboard(undefined);
    setExecCommand(() => false);
    expect(await copyText(URL_TEXT)).toBe(false);
  });
});
