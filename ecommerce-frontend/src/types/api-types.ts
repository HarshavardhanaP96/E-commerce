import { Bar, Coupon, Line, Order, Pie, Product, ShippingInfo, Stats, User } from "./types";

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
};
export type ConfirmOrderResponse = MessageResponse & { orderId: string };

export type CouponsResponse = { success: boolean; coupons: Coupon[] };
export type DiscountResponse = { success: boolean; discount: number };

export type StatsResponse = { success: boolean; stats: Stats };
export type PieResponse = { success: boolean; charts: Pie };
export type BarResponse = { success: boolean; charts: Bar };
export type LineResponse = { success: boolean; charts: Line };
