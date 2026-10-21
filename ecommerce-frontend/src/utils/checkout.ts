export type CheckoutStep = "cart" | "address" | "payment";

const STEPS: { key: CheckoutStep; label: string }[] = [
  { key: "cart", label: "Cart" },
  { key: "address", label: "Address" },
  { key: "payment", label: "Payment" },
];

export const checkoutSteps = (current: CheckoutStep) => {
  const at = STEPS.findIndex((s) => s.key === current);
  return STEPS.map((s, i) => ({ ...s, state: i < at ? "done" : i === at ? "current" : "upcoming" } as const));
};
