/** The shop dashboard's sections (GET /dashboard/{section}); revenue = gross value of orders placed in the range. */
export type ProductsSection = {
  top: { productId: string; name: string; units: number; revenue: number; orders: number }[];
  categories: { category: string; revenue: number; units: number; share: number }[];
  stock: { productId: string; name: string; variantId: string | null; label: string | null; stock: number;
    threshold: number; unitsSold: number; daysLeft: number | null }[];
  idle: { productId: string; name: string; stock: number }[];
  /** Against the same number of days before the range; `change` is null when `isNew`. */
  trending: { rising: TrendRow[]; falling: TrendRow[] };
  boughtTogether: { productA: string; nameA: string; productB: string; nameB: string; orders: number }[];
};

export type TrendRow = {
  productId: string; name: string; units: number; previousUnits: number; change: number | null; isNew: boolean;
};

export type CustomersSection = {
  customers: number;
  newCustomers: number;
  returningCustomers: number;
  repeatRate: number;
  top: { userId: string; name: string | null; email: string | null; orders: number; revenue: number }[];
  places: { state: string | null; city: string | null; orders: number; revenue: number }[];
  audience: Audience;
};

/**
 * `small` = hidden, every figure null: fewer than 5 buyers, or a cell (of any size) the totals would give such a
 * group away from.
 * The grand total is never hidden. `share` = percent of the range's buyer revenue.
 */
export type AudienceCell = {
  buyers: number | null; orders: number | null; revenue: number | null; share: number | null; small: boolean;
};

/** `gender` is female, male, unknown or "all" (the column totals); `cells` follow `Audience.bands`. */
export type AudienceRow = { gender: string; cells: AudienceCell[]; total: AudienceCell };

/** Sales by gender and age band; age in whole years on `asOf`, the range's last day. */
export type Audience = { asOf: string; bands: string[]; rows: AudienceRow[] };

export type CouponsSection = {
  ordersWithCoupon: number;
  shareOfOrders: number;
  discount: number;
  codes: { code: string; uses: number; discount: number; revenue: number }[];
};

export type PaymentsSection = {
  granularity: "day" | "week" | "month";
  methods: { method: string; orders: number; amount: number; share: number }[];
  refunds: { start: string; count: number; amount: number }[];
};

export type TaxSection = {
  sellerState: string | null;
  rows: { month: string; rate: number; taxableValue: number; cgst: number; sgst: number; igst: number; tax: number }[];
  totalTaxable: number;
  totalTax: number;
};
