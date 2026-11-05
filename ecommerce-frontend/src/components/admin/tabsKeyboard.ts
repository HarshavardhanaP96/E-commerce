/** WAI-ARIA tabs: Left/Right wrap, Home/End jump. Null for keys the tab list does not handle. */
export const nextTabIndex = (key: string, current: number, count: number): number | null => {
  switch (key) {
    case "ArrowRight":
      return (current + 1) % count;
    case "ArrowLeft":
      return (current - 1 + count) % count;
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
};
