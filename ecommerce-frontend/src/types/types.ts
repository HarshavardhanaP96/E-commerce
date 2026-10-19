export type Role = "admin" | "user";

export type User = {
  _id: string;
  name: string;
  email: string;
  photo: string;
  gender?: "male" | "female";
  role: Role;
  dob: string;
  age: number;
  createdAt?: string;
  phone?: string;
  locale?: string;
  emailVerified?: boolean;
  status?: "ACTIVE" | "SUSPENDED";
  lastLoginAt?: string;
};

export type SavedAddress = {
  id: string;
  label?: string;
  name: string;
  phone: string;
  line1: string;
  line2?: string;
  city: string;
  state: string;
  country: string;
  pinCode: string;
  isDefault: boolean;
};

export type SavedAddressInput = Omit<SavedAddress, "id">;

export type Product = {
  _id: string;
  name: string;
  price: number;
  stock: number;
  category: string;
  photo: string;
  createdAt?: string;
  taxRate?: number;
  slug?: string;
  sku?: string;
  description?: string;
  brand?: string;
  mrp?: number;
  status?: "DRAFT" | "ACTIVE" | "ARCHIVED";
  tags?: string[];
  images?: string[];
  categoryId?: string | null;
};

export type ShippingInfo = {
  address: string;
  city: string;
  state: string;
  country: string;
  pinCode: string;
  name?: string;
  phone?: string;
};

export type CartItem = {
  productId: string;
  photo: string;
  name: string;
  price: number;
  quantity: number;
  stock: number;
  taxRate?: number;
};

export type OrderItem = {
  productId: string;
  photo: string;
  name: string;
  price: number;
  quantity: number;
  sku?: string;
  taxRate?: number;
};

export type OrderStatus = "Processing" | "Shipped" | "Delivered";

export type OrderStatusEntry = { status: OrderStatus; at: string; actor: string };
export type OrderCustomer = { name?: string; email?: string; phone?: string };
export type OrderRefund = { amount: number; reason: string; status: string; at: string };
export type OrderPayment = {
  provider: string;
  status?: "PAID" | "PARTIALLY_REFUNDED" | "REFUNDED" | string;
  amount?: number;
  currency?: string;
  paidAt?: string;
  method?: string | null;
  refunded?: number;
  refunds?: OrderRefund[];
  /** Admin only: why the last refund attempt failed. */
  failureReason?: string | null;
};

export type InvoiceParty = { name?: string; address?: string; gstin?: string; state?: string; email?: string; phone?: string };
export type Invoice = {
  number: string;
  date: string;
  orderNumber?: string;
  seller: InvoiceParty;
  buyer?: { name?: string; email?: string; phone?: string } | null;
  billTo?: ShippingInfo | null;
  shipTo?: ShippingInfo | null;
  lines: { description: string; sku?: string; quantity: number; unitPrice: number; amount: number; taxRate: number }[];
  taxSummary: { rate: number; taxableValue: number; cgst: number; sgst: number; igst: number }[];
  subtotal: number;
  discount: number;
  shippingCharges: number;
  tax: number;
  total: number;
  paymentMethod?: string | null;
  paymentStatus?: string | null;
  refunded: number;
};

export type Category = {
  _id: string;
  name: string;
  slug: string;
  parentId?: string | null;
  image?: string | null;
  createdAt?: string;
};

export type Order = {
  _id: string;
  orderItems: OrderItem[];
  shippingInfo: ShippingInfo;
  subtotal: number;
  tax: number;
  shippingCharges: number;
  discount: number;
  total: number;
  status: OrderStatus;
  user: { _id: string; name: string };
  createdAt: string;
  orderNumber?: string;
  billingInfo?: ShippingInfo | null;
  customer?: OrderCustomer | null;
  statusHistory: OrderStatusEntry[];
  couponCode?: string;
  notes?: string;
  payment?: OrderPayment | null;
};

export type CouponStatus = "active" | "scheduled" | "expired" | "exhausted" | "inactive";

export type Coupon = {
  _id: string;
  coupon: string;
  amount: number;
  type: "FLAT" | "PERCENT";
  maxDiscount?: number;
  minOrderValue?: number;
  startsAt?: string;
  expiresAt?: string;
  usageLimit?: number;
  usedCount: number;
  perUserLimit?: number;
  active: boolean;
  status: CouponStatus;
  productIds: string[];
  categories: string[];
  firstOrderOnly: boolean;
};

export type NewCouponInput = {
  coupon: string;
  amount: number;
  type?: "FLAT" | "PERCENT";
  maxDiscount?: number;
  minOrderValue?: number;
  startsAt?: string;
  expiresAt?: string;
  usageLimit?: number;
  perUserLimit?: number;
  active?: boolean;
  productIds?: string[];
  categories?: string[];
  firstOrderOnly?: boolean;
};

type CountAndChange = {
  revenue: number;
  product: number;
  user: number;
  order: number;
};

export type CategoryShare = { category: string; percent: number };

export type LatestTransaction = {
  id: string;
  amount: number;
  discount: number;
  quantity: number;
  status: OrderStatus;
};

export type Stats = {
  count: CountAndChange;
  changePercent: CountAndChange;
  chart: { order: number[]; revenue: number[] };
  categoryCount: CategoryShare[];
  genderRatio: { male: number; female: number };
  latestTransaction: LatestTransaction[];
};

export type Pie = {
  orderFulfillment: { processing: number; shipped: number; delivered: number };
  productCategories: CategoryShare[];
  stockAvailability: { inStock: number; outOfStock: number };
  revenueDistribution: {
    totalTax: number;
    totalDiscount: number;
    totalShippingCharges: number;
    totalIncome: number;
  };
  ageGroup: { teen: number; adult: number; old: number };
  userCategory: { admin: number; customer: number };
};

export type Bar = { users: number[]; products: number[]; orders: number[] };

export type Line = { users: number[]; products: number[]; discount: number[]; revenue: number[] };

export type DashboardScope = { productId?: string; category?: string; userId?: string };
