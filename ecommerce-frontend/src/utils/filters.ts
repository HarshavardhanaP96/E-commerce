export const MAX_PRICE = 100000;

/** "" is the server's default order: newest first. */
export const SORTS = [
  { value: "", label: "Newest" },
  { value: "asc", label: "Price: low to high" },
  { value: "dsc", label: "Price: high to low" },
] as const;

type FilterState = { search: string; category: string; maxPrice: number; picked: Record<string, string> };

/** The filters currently narrowing the list, as removable chips. */
export const activeFilters = (s: FilterState, money: (n: number) => string) => {
  const out: { key: string; label: string }[] = [];
  if (s.search.trim()) out.push({ key: "search", label: `“${s.search.trim()}”` });
  if (s.category) out.push({ key: "category", label: s.category.charAt(0).toUpperCase() + s.category.slice(1) });
  if (s.maxPrice < MAX_PRICE) out.push({ key: "price", label: `Up to ${money(s.maxPrice)}` });
  for (const [name, value] of Object.entries(s.picked)) out.push({ key: `option:${name}`, label: `${name}: ${value}` });
  return out;
};
