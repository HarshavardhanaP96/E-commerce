import { describe, expect, it } from "vitest";
import { emptyMessage } from "./table";

describe("emptyMessage", () => {
  it("uses the page's own empty text when there are no records at all", () => {
    expect(emptyMessage(0, "No products yet.")).toEqual({ text: "No products yet.", nothingYet: true });
  });
  it("says nothing matched when records exist but a search or filter hides them", () => {
    expect(emptyMessage(12, "No products yet.")).toEqual({ text: "Nothing matches your search or filters.", nothingYet: false });
  });
});
