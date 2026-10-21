import { CartItem, OptionAxis, Product, Variant } from "../types/types";

/** What the shopper has picked so far: axis name → option value label. */
export type Selection = Record<string, string>;

export const hasVariants = (p: Pick<Product, "variants">) => (p.variants?.items.length ?? 0) > 0;

const sellable = (v: Variant) => v.status === "ACTIVE";

/** A cart line is a product plus, when it has variants, the chosen variant. */
export const cartKey = (i: { productId: string; variantId?: string }) => `${i.productId}:${i.variantId ?? ""}`;

/**
 * Lowest and highest price a shopper can pay, over the sellable variants that fit the options picked so far
 * (the product price when there are none).
 */
export const priceRange = (p: Product, selection: Selection = {}): { min: number; max: number } => {
  const prices = (p.variants?.items ?? [])
    .filter((v) => sellable(v) && v.options.every((o) => !selection[o.name] || selection[o.name] === o.value))
    .map((v) => v.price);
  return prices.length ? { min: Math.min(...prices), max: Math.max(...prices) } : { min: p.price, max: p.price };
};

/** The variant that matches every axis of the selection, or undefined while the selection is incomplete. */
export const findVariant = (p: Product, selection: Selection): Variant | undefined => {
  const { axes = [], items = [] } = p.variants ?? {};
  if (!axes.length || axes.some((a) => !selection[a.name])) return undefined;
  return items.find((v) => v.options.every((o) => selection[o.name] === o.value));
};

/**
 * Can this value still be bought given the other axes already picked? Used to grey out sold-out sizes
 * or colours without hiding them.
 */
export const isValueAvailable = (p: Product, axis: OptionAxis, label: string, selection: Selection): boolean =>
  (p.variants?.items ?? []).some(
    (v) =>
      sellable(v) &&
      v.stock > 0 &&
      v.options.some((o) => o.name === axis.name && o.value === label) &&
      v.options.every((o) => o.name === axis.name || !selection[o.name] || selection[o.name] === o.value)
  );

/** Axes with a single value need no choice; start with those selected. */
export const defaultSelection = (p: Product): Selection =>
  Object.fromEntries((p.variants?.axes ?? []).filter((a) => a.values.length === 1).map((a) => [a.name, a.values[0].label]));

/** Images for the gallery: those of the picked option values first, then the product's own. */
export const galleryImages = (p: Product, selection: Selection): string[] => {
  const picked = (p.variants?.axes ?? []).flatMap(
    (a) => a.values.find((v) => v.label === selection[a.name])?.images ?? []
  );
  return [...new Set([...picked, p.photo, ...(p.images ?? [])].filter(Boolean))];
};

/** Swatch colours of the product's colour axis, for product cards. */
export const swatches = (p: Product): { label: string; hex: string }[] =>
  (p.variants?.axes ?? [])
    .filter((a) => a.type === "COLOUR")
    .flatMap((a) => a.values)
    .filter((v): v is typeof v & { hex: string } => !!v.hex)
    .map((v) => ({ label: v.label, hex: v.hex }));

export const inStock = (p: Product) => p.stock > 0;

export const discountPercent = (price: number, mrp?: number | null) =>
  mrp && mrp > price ? Math.round(((mrp - price) / mrp) * 100) : 0;

/** What a product card shows: the price (lowest variant's, marked "from" when variants differ), and MRP/% off for plain products. */
export const cardPricing = (p: Product) => {
  if (hasVariants(p)) {
    const { min, max } = priceRange(p);
    return { price: min, from: min !== max, mrp: null, off: 0 };
  }
  const off = discountPercent(p.price, p.mrp);
  return { price: p.price, from: false, mrp: off > 0 ? (p.mrp as number) : null, off };
};

/** Units of this line already in the cart, so "add" tops up instead of replacing. */
export const quantityInCart = (cart: CartItem[], key: string) =>
  cart.find((i) => cartKey(i) === key)?.quantity ?? 0;
