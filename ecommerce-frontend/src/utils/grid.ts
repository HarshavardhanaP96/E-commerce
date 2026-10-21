/** How many columns a trailing tile spans so the grid's last row is never left short. */
export const fillSpan = (count: number, columns: number) => {
  if (columns < 1) throw new Error("columns must be at least 1");
  const rest = count % columns;
  return rest === 0 ? columns : columns - rest;
};
