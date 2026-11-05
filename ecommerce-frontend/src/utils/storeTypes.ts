import type { StoreTypeKey } from "../redux/api/storeAPI";

const TYPE_LABELS: Record<StoreTypeKey, string> = {
  GENERAL: "General",
  FASHION: "Fashion",
  GROCERY: "Grocery",
  ELECTRONICS: "Electronics",
};

/** Kept outside the platform chunk so the store admin can name its type too. */
export const typeLabel = (type: StoreTypeKey | undefined) => TYPE_LABELS[type ?? "GENERAL"];
