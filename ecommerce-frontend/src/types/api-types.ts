import { Bar, Category, Coupon, Invoice, Line, OptionFacet, Order, OrderPayment, Pie, Product, SavedAddress, ShippingInfo, SizeChart, StockAlert, Stats, User, VariantSales } from "./types";

export type MessageResponse = { success: boolean; message: string };

export type UserResponse = { success: boolean; user: User };
export type UsersResponse = { success: boolean; users: User[] };
export type AuthResponse = { success: boolean; message: string; token: string; user: User };

export type ProductsResponse = { success: boolean; products: Product[] };
export type ProductResponse = { success: boolean; product: Product };
export type ProductSavedResponse = ProductResponse & { message: string };
export type VariantsRequest = {
  axes: { name: string; type: "text" | "colour"; values: { label: string; hex?: string; images?: string[] }[] }[];
  variants: {
    id?: string;
    sku?: string;
    options: { name: string; value: string }[];
    price: number;
    mrp?: number;
    stock?: number;
    status: "ACTIVE" | "DISABLED";
  }[];
};
export type StockAlertsResponse = { success: boolean; alerts: StockAlert[] };
export type VariantSalesResponse = { success: boolean; sales: VariantSales[] };
export type CsvChange = { line: number; productId: string; product: string; variant: string | null; changes: string[] };
export type CsvImportResponse = { success: boolean; applied: boolean; changes: CsvChange[]; unchanged: number };
export type CsvImportError = { success: false; message: string; errors?: { line: number; message: string }[] };
export type SizeChartRequest = SizeChart;
export type OptionFacetsResponse = { success: boolean; options: OptionFacet[] };
export type CategoriesResponse = { success: boolean; categories: string[] };
export type SearchProductsResponse = ProductsResponse & { totalPage: number; total: number };
export type SearchProductsRequest = {
  price: number;
  page: number;
  category: string;
  search: string;
  sort: string;
  /** Selected options as "Name:Value"; one variant must match all of them. */
  options?: string[];
};

export type OrdersResponse = { success: boolean; orders: Order[] };
export type OrderResponse = { success: boolean; order: Order };
export type InvoiceResponse = { success: boolean; invoice: Invoice };
export type RefundResponse = MessageResponse & { payment: OrderPayment };
export type CategoryResponse = MessageResponse & { category: Category };
export type CategoryListResponse = { success: boolean; categories: Category[] };

export type Quote = { subtotal: number; tax: number; shippingCharges: number; discount: number; total: number };
export type QuoteRequest = { items: { productId: string; quantity: number }[]; coupon?: string };
export type QuoteResponse = { success: boolean; quote: Quote };

export type CheckoutRequest = {
  items: { productId: string; variantId?: string; quantity: number }[];
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

export type StatsResponse = { success: boolean; stats: Stats };
export type PieResponse = { success: boolean; charts: Pie };
export type BarResponse = { success: boolean; charts: Bar };
export type LineResponse = { success: boolean; charts: Line };

export type AddressesResponse = { success: boolean; addresses: SavedAddress[] };
export type AddressResponse = { success: boolean; message: string; address: SavedAddress };
