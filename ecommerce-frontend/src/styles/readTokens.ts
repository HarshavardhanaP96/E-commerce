// Test helper: reads the custom properties declared in _tokens.scss, so tests check the real values.
// Read from disk: Vitest blanks style files on import, even with ?raw.
import { readFileSync } from "node:fs";

const SOURCE = readFileSync(new URL("./_tokens.scss", import.meta.url), "utf8");

/** The `--name: value` pairs in the block whose selector is exactly `selector` (starting a line). */
export const tokenBlock = (selector: string): Record<string, string> => {
  const start = SOURCE.indexOf(`\n${selector} {`);
  if (start < 0) throw new Error(`No token block for ${selector}`);
  const body = SOURCE.slice(start, SOURCE.indexOf("\n}", start));
  return Object.fromEntries([...body.matchAll(/(--[a-z0-9-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
};

export const SHARED = () => tokenBlock(":root");
export const STORE = () => tokenBlock(':root,\n[data-surface="store"]');
export const ADMIN = () => tokenBlock('[data-surface="admin"]');
