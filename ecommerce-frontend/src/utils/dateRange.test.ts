import assert from "node:assert/strict";
import { test } from "vitest";
import { isoDay, presetRange, todayIn } from "./dateRange";

const today = new Date(2026, 9, 20); // 20 Oct 2026, local time

test("last 7 and 30 days end today", () => {
  assert.deepEqual(presetRange("7d", today), { from: "2026-10-14", to: "2026-10-20" });
  assert.deepEqual(presetRange("30d", today), { from: "2026-09-21", to: "2026-10-20" });
});

test("this month runs to today, last month is the whole month", () => {
  assert.deepEqual(presetRange("month", today), { from: "2026-10-01", to: "2026-10-20" });
  assert.deepEqual(presetRange("lastMonth", today), { from: "2026-09-01", to: "2026-09-30" });
  assert.deepEqual(presetRange("lastMonth", new Date(2026, 0, 15)), { from: "2025-12-01", to: "2025-12-31" });
});

test("quarter starts on the first day of the calendar quarter", () => {
  assert.deepEqual(presetRange("quarter", today), { from: "2026-10-01", to: "2026-10-20" });
  assert.deepEqual(presetRange("quarter", new Date(2026, 4, 9)), { from: "2026-04-01", to: "2026-05-09" });
});

test("financial year starts on 1 April", () => {
  assert.deepEqual(presetRange("fy", today), { from: "2026-04-01", to: "2026-10-20" });
  assert.deepEqual(presetRange("fy", new Date(2027, 1, 10)), { from: "2026-04-01", to: "2027-02-10" });
  assert.deepEqual(presetRange("fy", new Date(2026, 3, 1)), { from: "2026-04-01", to: "2026-04-01" });
});

test("today is the store's calendar day, not the browser's", () => {
  const instant = new Date("2026-10-05T20:00:00Z"); // 6 Oct 01:30 in India, still 5 Oct in New York
  assert.equal(isoDay(todayIn("Asia/Kolkata", instant)), "2026-10-06");
  assert.equal(isoDay(todayIn("America/New_York", instant)), "2026-10-05");
});

test("an unknown zone falls back to the browser's day", () => {
  const instant = new Date(2026, 9, 20, 12);
  assert.equal(isoDay(todayIn("Mars/Olympus", instant)), "2026-10-20");
});
