// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "../test/dom";
import { UnsavedContext, useUnsavedGuard } from "./useUnsavedGuard";

const Form = ({ dirty }: { dirty: boolean }) => {
  useUnsavedGuard(dirty);
  return null;
};

describe("useUnsavedGuard", () => {
  afterEach(cleanup);

  it("tells the surrounding page whether the form has unsaved changes", async () => {
    const seen: boolean[] = [];
    const report = (d: boolean) => seen.push(d);
    await render(<UnsavedContext.Provider value={report}><Form dirty={false} /></UnsavedContext.Provider>);
    expect(seen.at(-1)).toBe(false);
    cleanup();
    await render(<UnsavedContext.Provider value={report}><Form dirty /></UnsavedContext.Provider>);
    expect(seen.at(-1)).toBe(true);
  });

  it("reports clean again when the form goes away", async () => {
    const seen: boolean[] = [];
    const report = (d: boolean) => seen.push(d);
    await render(<UnsavedContext.Provider value={report}><Form dirty /></UnsavedContext.Provider>);
    cleanup(); // the tab unmounts, e.g. on a tab switch
    expect(seen.at(-1)).toBe(false);
  });
});
