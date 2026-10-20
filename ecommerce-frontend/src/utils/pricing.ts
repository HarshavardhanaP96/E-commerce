/** The store's shop rules the storefront shows before the server prices anything. Filled from the store's config. */
export const commerce = { shippingFee: 200, freeShippingAbove: 1000, defaultTaxRate: 18, minimumAge: 18 };

export type PricingRules = { shippingFee: number; freeShippingAbove: number; defaultTaxRate: number };
type Line = { price: number; quantity: number; taxRate?: number | null };

/** Mirrors the server's PriceCalculator for display only; the server recomputes at checkout. */
export const cartTotals = (lines: Line[], discount: number, rules: PricingRules) => {
  const subtotal = lines.reduce((total, item) => total + item.price * item.quantity, 0);
  const shippingCharges = subtotal === 0 || subtotal > rules.freeShippingAbove ? 0 : rules.shippingFee;
  const taxableByRate = new Map<number, number>();
  for (const item of lines) {
    const rate = item.taxRate ?? rules.defaultTaxRate;
    taxableByRate.set(rate, (taxableByRate.get(rate) ?? 0) + item.price * item.quantity);
  }
  const tax = [...taxableByRate].reduce((sum, [rate, amount]) => sum + Math.round((amount * rate) / 100), 0);
  const total = subtotal + tax + shippingCharges - Math.min(discount, subtotal);
  return { subtotal, tax, shippingCharges, total };
};

/** The store's shipping rule in a sentence, for the help centre and the terms. */
export const shippingNote = (rules: Pick<PricingRules, "shippingFee" | "freeShippingAbove">,
                             money: (n: number) => string) =>
  rules.shippingFee === 0
    ? "Shipping is free on every order."
    : `Shipping is free on orders over ${money(rules.freeShippingAbove)}. Otherwise there is a flat ${money(rules.shippingFee)} charge, shown before you pay.`;
