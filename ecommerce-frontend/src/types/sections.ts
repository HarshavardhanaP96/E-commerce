/** The shop dashboard's sections (GET /dashboard/{section}); revenue = gross value of orders placed in the range. */
export type ProductsSection = {
  top: { productId: string; name: string; units: number; revenue: number; orders: number }[];
  categories: { category: string; revenue: number; units: number; share: number }[];
  stock: { productId: string; name: string; variantId: string | null; label: string | null; stock: number;
    threshold: number; unitsSold: number; daysLeft: number | null }[];
  idle: { productId: string; name: string; stock: number }[];
};

export type CustomersSection = {
  customers: number;
  newCustomers: number;
  returningCustomers: number;
  repeatRate: number;
  top: { userId: string; name: string | null; email: string | null; orders: number; revenue: number }[];
  places: { state: string | null; city: string | null; orders: number; revenue: number }[];
};

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
