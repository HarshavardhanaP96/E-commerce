/** An empty table's message: the page's own text when there are no records, else "nothing matches" (no add button). */
export const emptyMessage = (totalRecords: number, text: string) =>
  totalRecords > 0
    ? { text: "Nothing matches your search or filters.", nothingYet: false }
    : { text, nothingYet: true };
