type Query = Pick<MediaQueryList, "matches"> & {
  addEventListener: (type: "change", listener: (e: { matches: boolean }) => void) => void;
  removeEventListener: (type: "change", listener: (e: { matches: boolean }) => void) => void;
};

/**
 * Calls `close` whenever `query` starts matching (and at once if it already does); returns the unsubscribe.
 * Used to shut a phone-only modal when the window widens, e.g. a tablet rotated to landscape.
 */
export const closeWhenWide = (query: Query, close: () => void) => {
  const onChange = (e: { matches: boolean }) => e.matches && close();
  if (query.matches) close();
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
};
