import { describe, expect, it, vi } from "vitest";
import { closeWhenWide } from "./media";

const fakeQuery = (matches: boolean) => {
  let listener: ((e: { matches: boolean }) => void) | undefined;
  return {
    matches,
    addEventListener: (_: string, l: (e: { matches: boolean }) => void) => (listener = l),
    removeEventListener: vi.fn(),
    fire: (m: boolean) => listener?.({ matches: m }),
  };
};

describe("closeWhenWide", () => {
  it("closes the phone panel when the window widens past the breakpoint", () => {
    const q = fakeQuery(false);
    const close = vi.fn();
    closeWhenWide(q, close);
    q.fire(false);
    expect(close).not.toHaveBeenCalled();
    q.fire(true);
    expect(close).toHaveBeenCalledTimes(1);
  });

  it("closes at once if the window is already wide, and unsubscribes on cleanup", () => {
    const q = fakeQuery(true);
    const close = vi.fn();
    const stop = closeWhenWide(q, close);
    expect(close).toHaveBeenCalledTimes(1);
    stop();
    expect(q.removeEventListener).toHaveBeenCalled();
  });
});
