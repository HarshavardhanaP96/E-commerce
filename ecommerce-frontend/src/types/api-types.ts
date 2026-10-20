import { Bar, Coupon, Line, Order, Pie, Product, SavedAddress, ShippingInfo, Stats, User } from "./types";

export type MessageResponse = { success: boolean; message: string };

export type UserResponse = { success: boolean; user: User };
export type UsersResponse = { success: boolean; users: User[] };
export type AuthResponse = { success: boolean; message: string; token: string; user: User };

export type ProductsResponse = { success: boolean; products: Product[] };
export type ProductResponse = { success: boolean; product: Product };
export type CategoriesResponse = { success: boolean; categories: string[] };
export type SearchProductsResponse = ProductsResponse & { totalPage: number; total: number };
export type SearchProductsRequest = {
  price: number;
  page: number;
  category: string;
  search: string;
  sort: string;
};

export type OrdersResponse = { success: boolean; orders: Order[] };
export type OrderResponse = { success: boolean; order: Order };

export type CheckoutRequest = {
  items: { productId: string; quantity: number }[];
  coupon?: string;
};
export type Checkout = {
  provider: "razorpay" | "fake";
  key: string;
  providerOrderId: string;
  amount: number; // paise
  currency: string;
  subtotal: number;
  tax: number;
  shippingCharges: number;
  discount: number;
  total: number;
};
export type CheckoutResponse = { success: boolean; checkout: Checkout };
export type ConfirmOrderRequest = {
  providerOrderId: string;
  paymentId: string;
  signature: string;
  shippingInfo: ShippingInfo;
  billingInfo?: ShippingInfo;
  notes?: string;
};
export type ConfirmOrderResponse = MessageResponse & { orderId: string };

export type CouponsResponse = { success: boolean; coupons: Coupon[] };
export type DiscountResponse = { success: boolean; discount: number };

export type StatsResponse = { success: boolean; stats: Stats };

/** refunds, discount and tax are absent when the view is narrowed to a product or category. */
export type SummaryKpis = {
  revenue: number;
  orders: number;
  averageOrderValue: number;
  units: number;
  refunds?: number | null;
  discount?: number | null;
  tax?: number | null;
};
export type SummaryChange = { revenue: number; orders: number; averageOrderValue: number; units: number };
export type Summary = {
  from: string;
  to: string;
  timezone: string;
  granularity: "day" | "week" | "month";
  basis: "gross" | "net";
  compareFrom?: string | null;
  compareTo?: string | null;
  current: SummaryKpis;
  previous?: SummaryKpis | null;
  changePercent?: SummaryChange | null;
  series: { start: string; revenue: number; orders: number }[];
};
export type SummaryResponse = { success: boolean; summary: Summary };
export type PieResponse = { success: boolean; charts: Pie };
export type BarResponse = { success: boolean; charts: Bar };
export type LineResponse = { success: boolean; charts: Line };

export type AddressesResponse = { success: boolean; addresses: SavedAddress[] };
export type AddressResponse = { success: boolean; message: string; address: SavedAddress };
