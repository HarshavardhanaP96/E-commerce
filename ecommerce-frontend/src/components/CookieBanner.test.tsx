// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import CookieBanner from "./CookieBanner";
import Footer from "./footer";
import { ALL, NONE, currentChoice, saveConsent } from "../utils/consent";
import { button, cleanup, click, field, queryButton, render } from "../test/dom";

const show = (path = "/") =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Footer />
      <CookieBanner />
    </MemoryRouter>,
  );

const banner = () => document.querySelector(".cookie-banner");

beforeEach(() => localStorage.clear());
afterEach(cleanup);

describe("CookieBanner", () => {
  it("shows a bottom bar with three equal choices on the first visit, without blocking the page", async () => {
    await show();
    expect(banner()).not.toBeNull();
    expect(document.querySelector("dialog")).toBeNull();
    const buttons = [...banner()!.querySelectorAll(".cookie-banner__actions button")];
    expect(buttons.map((b) => b.textContent)).toEqual(["Reject all", "Customise", "Accept all"]);
    expect(new Set(buttons.map((b) => b.className)).size).toBe(1); // same weight: no highlighted "Accept"
  });

  it("Accept all grants every category and closes", async () => {
    await show();
    await click(button("Accept all"));
    expect(currentChoice()).toEqual(ALL);
    expect(banner()).toBeNull();
  });

  it("Reject all records the refusal and closes", async () => {
    await show();
    await click(button("Reject all"));
    expect(currentChoice()).toEqual(NONE);
    expect(banner()).toBeNull();
  });

  it("Customise starts with nothing ticked and saves only what was ticked", async () => {
    await show();
    await click(button("Customise"));
    expect(field("Strictly necessary").disabled).toBe(true);
    for (const name of ["Functional", "Analytics", "Marketing"]) expect(field(name).checked).toBe(false);
    await click(field("Analytics"));
    await click(button("Save choices"));
    expect(currentChoice()).toEqual({ functional: false, analytics: true, marketing: false });
  });

  it("stays away once chosen, and the footer's Cookie settings reopens it with the current choice", async () => {
    saveConsent({ functional: false, analytics: false, marketing: true });
    await show();
    expect(banner()).toBeNull();
    await click(button("Cookie settings"));
    expect(banner()).not.toBeNull();
    expect(field("Marketing").checked).toBe(true);
    expect(field("Analytics").checked).toBe(false);
    expect(document.activeElement?.id).toBe("cookie-banner-title");
    await click(field("Marketing"));
    await click(button("Save choices"));
    expect(currentChoice()?.marketing).toBe(false);
  });

  it("is not shown in the admin area", async () => {
    await show("/admin/analytics");
    expect(banner()).toBeNull();
    expect(queryButton("Accept all")).toBeNull();
  });
});
