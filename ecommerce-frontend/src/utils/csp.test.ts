// The storefront tags (utils/tracking.ts) only run if nginx's Content-Security-Policy allows their hosts.
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const template = readFileSync(new URL("../../nginx.conf.template", import.meta.url), "utf8");
const policies = [...template.matchAll(/Content-Security-Policy "([^"]+)"/g)].map((m) => m[1]);
const directive = (csp: string, name: string) =>
  csp.split(";").map((d) => d.trim().split(/\s+/)).find((d) => d[0] === name)?.slice(1) ?? [];

describe("nginx Content-Security-Policy", () => {
  it("is the same on the server block and on the SPA location (nginx drops inherited headers)", () => {
    expect(policies).toHaveLength(2);
    expect(policies[0]).toBe(policies[1]);
  });

  it.each([
    ["script-src", ["https://*.googletagmanager.com", "https://www.googleadservices.com", "https://googleads.g.doubleclick.net",
      "https://www.google.com", "https://connect.facebook.net", "https://checkout.razorpay.com"]],
    ["connect-src", ["https://*.google-analytics.com", "https://*.analytics.google.com", "https://*.googletagmanager.com",
      // Google Ads conversions also call ad.doubleclick.net, not only *.g.doubleclick.net.
      "https://*.doubleclick.net", "https://*.google.com", "https://pagead2.googlesyndication.com",
      "https://www.facebook.com", "https://connect.facebook.net", "https://api.razorpay.com"]],
    ["frame-src", ["https://td.doubleclick.net", "https://www.googletagmanager.com", "https://checkout.razorpay.com"]],
  ])("%s allows the Google, Meta and Razorpay hosts", (name, hosts) => {
    for (const csp of policies) expect(directive(csp, name)).toEqual(expect.arrayContaining(hosts));
  });

  it("still allows no inline or eval script", () => {
    for (const csp of policies) {
      expect(directive(csp, "script-src")).not.toContain("'unsafe-inline'");
      expect(directive(csp, "script-src")).not.toContain("'unsafe-eval'");
    }
  });
});
