import { createContext, useContext, useEffect } from "react";

/** A page with tabs provides this to learn whether the open tab has unsaved changes (to ask before switching). */
export const UnsavedContext = createContext<((dirty: boolean) => void) | null>(null);

// shortcut: in-app navigation away from a dirty form is not blocked (BrowserRouter has no useBlocker); move to a data router to block it.
/** Warns before closing or reloading the page while a form has unsaved changes, and tells a surrounding tab page. */
export const useUnsavedGuard = (dirty: boolean) => {
  const report = useContext(UnsavedContext);

  useEffect(() => {
    report?.(dirty);
    return () => report?.(false);
  }, [dirty, report]);

  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
};
