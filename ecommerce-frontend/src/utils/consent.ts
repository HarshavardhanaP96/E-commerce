/**
 * Single gate for optional (non-essential) browser storage and third-party scripts
 * (compliance-consent.md §5). Today nothing optional exists, so every category is off and no banner is shown.
 *
 * Adding analytics or marketing later: build the opt-in banner first, store the choice here, and load the
 * script only when hasConsent("analytics") is true. Never add such tags straight to index.html.
 */
export type ConsentCategory = "functional" | "analytics" | "marketing";

export const hasConsent = (category: ConsentCategory): boolean => {
  void category;
  return false;
};
