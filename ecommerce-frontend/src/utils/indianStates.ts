import type { IndianState } from "../redux/api/referenceAPI";

const key = (s: string) => s.toLowerCase().replace(/&/g, "and").replace(/[^a-z]/g, "");

/** The official name to pre-select for a typed or saved state, or "" when it is not on the list. */
export const matchState = (raw: string, states: IndianState[]) => {
  const k = key(raw);
  if (!k) return "";
  return states.find((s) => key(s.name) === k || key(s.code) === k)?.name ?? "";
};
