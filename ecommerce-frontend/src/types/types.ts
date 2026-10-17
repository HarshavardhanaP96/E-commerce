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
  slug?: string;
  sku?: string;
  description?: string;
  brand?: string;
  mrp?: number;
  status?: "DRAFT" | "ACTIVE" | "ARCHIVED";
  tags?: string[];
  images?: string[];
};

export type ShippingInfo = {
  address: string;
  city: string;
  state: string;
  country: string;
  pinCode: string;
};

export type CartItem = {
  productId: string;
  photo: string;
  name: string;
  price: number;
  quantity: number;
  stock: number;
};

export type OrderItem = {
  productId: string;
  photo: string;
  name: string;
  price: number;
  quantity: number;
};

export type OrderStatus = "Processing" | "Shipped" | "Delivered";

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
};

export type Coupon = {
  _id: string;
  coupon: string;
  amount: number;
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
