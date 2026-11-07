import type { IndianState } from "../redux/api/referenceAPI";

const key = (s: string) => s.toLowerCase().replace(/&/g, "and").replace(/[^a-z]/g, "");

/** The official name to pre-select for a typed or saved state (name, code or old spelling), or "" when it is not on the list. */
export const matchState = (raw: string, states: IndianState[]) => {
  const k = key(raw);
  if (!k) return "";
  return states.find((s) => [s.name, s.code, ...s.aliases].some((v) => key(v) === k))?.name ?? "";
};

/**
 * What a state select shows for a saved value. A value the list does not recognise (or any value, when the list did
 * not load) stays visible as an extra option, so picking "Not set" is a real change rather than a hidden one.
 */
export const savedStateChoice = (saved: string, states: IndianState[]) => {
  const matched = matchState(saved, states);
  if (matched || !saved.trim()) return { value: matched, extra: null };
  const label = states.length > 0 ? `${saved.trim()} (not recognised, choose a state)` : saved.trim();
  return { value: saved, extra: { value: saved, label } };
};
