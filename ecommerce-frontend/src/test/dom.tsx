// Test helpers for rendering components in Vitest's jsdom without extra dependencies.
import { act, ReactNode } from "react";
import { createRoot, Root } from "react-dom/client";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root | null = null;
let host: HTMLElement | null = null;

/** Renders `ui` into a fresh element in <body>. Call cleanup() after each test. */
export const render = async (ui: ReactNode) => {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(ui));
  return host;
};

export const cleanup = () => {
  act(() => root?.unmount());
  host?.remove();
  root = null;
  host = null;
};

/** Lets pending fetches, timers and state updates finish. */
export const settle = () => act(async () => {
  await new Promise((r) => setTimeout(r, 0));
});

export const click = (el: Element) => act(async () => {
  (el as HTMLElement).click();
});

/** The button whose visible text is exactly `name`. Throws when there is none. */
export const button = (name: string, scope: ParentNode = document) => {
  const found = [...scope.querySelectorAll("button")].find((b) => b.textContent?.trim() === name);
  if (!found) throw new Error(`No button "${name}"`);
  return found;
};

export const queryButton = (name: string, scope: ParentNode = document) =>
  [...scope.querySelectorAll("button")].find((b) => b.textContent?.trim() === name) ?? null;

/** The input inside the <label> whose text starts with `text`. Throws when there is none. */
export const field = (text: string, scope: ParentNode = document) => {
  const label = [...scope.querySelectorAll("label")].find((l) => l.textContent?.trim().startsWith(text));
  const input = label?.querySelector("input");
  if (!input) throw new Error(`No field "${text}"`);
  return input;
};

const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;

/** Types into a controlled input the way React listens for it. */
export const type = (input: HTMLInputElement, value: string) => act(async () => {
  setValue.call(input, value);
  input.dispatchEvent(new Event("input", { bubbles: true }));
});
