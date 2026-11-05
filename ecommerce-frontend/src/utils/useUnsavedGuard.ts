import { useEffect } from "react";

// shortcut: in-app navigation away from a dirty form is not blocked (BrowserRouter has no useBlocker); move to a data router to block it.
/** Warns before closing or reloading the page while a form has unsaved changes. */
export const useUnsavedGuard = (dirty: boolean) => {
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
};
