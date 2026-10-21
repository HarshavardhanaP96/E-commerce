import { SummaryChange, SummaryKpis } from "./api-types";

/** Step 3's tabs (DashboardSectionsController): date range only. */
export type RangeSectionName = "products" | "customers" | "coupons" | "payments" | "tax";

/** Every Analytics tab; Overview, Sales, Orders and Location also take compare and category. */
export type SectionName = "overview" | "sales" | "orders" | "location" | RangeSectionName;

export type SectionParams = {
  from: string;
  to: string;
  compare: "previous" | "year" | "none";
  basis: "net" | "gross";
  category?: string;
  limit?: number;
};

export type SectionWindow = {
  from: string;
  to: string;
  compareFrom?: string | null;
  compareTo?: string | null;
  timezone: string;
  granularity: "day" | "week" | "month";
};

export type SectionResponse<T> = { success: boolean; section: T };

export type SeriesPoint = { start: string; revenue: number; orders: number };

export type OverviewSection = SectionWindow & {
  basis: "net" | "gross";
  current: SummaryKpis;
  previous?: SummaryKpis | null;
  change?: SummaryChange | null;
  series: SeriesPoint[];
  byWeekday: { weekday: number; orders: number; revenue: number }[];
  byHour: number[];
  heat: number[][];
};

export type SalesSection = SectionWindow & {
  basis: "net" | "gross";
  series: SeriesPoint[];
  previousSeries?: SeriesPoint[] | null;
  breakdown?: {
    charged: number; discount: number; tax: number; shipping: number; refunds: number; net: number;
    couponOrders: number; couponOrderShare: number;
  } | null;
};

type Leg = { medianHours?: number | null; p90Hours?: number | null; count: number };

export type OrdersSection = SectionWindow & {
  series: SeriesPoint[];
  previousSeries?: SeriesPoint[] | null;
  byStatus: Record<string, number>;
  fulfilment: {
    placedToShipped: Leg; shippedToDelivered: Leg; placedToDelivered: Leg; withoutHistory: number;
    deliveryTrend: { start: string; medianHours?: number | null }[];
  };
  stuck: { id: string; orderNumber?: string | null; placedAt: string; ageDays: number; total: number }[];
  stuckCount: number;
  stuckAfterDays: number;
  size: { averageItems: number; bands: Record<string, number> };
  refunds?: { orders: number; amount: number; rate: number } | null;
  truncated: boolean;
};

export type LocationParams = SectionParams & { country?: string; state?: string; pinPrefix?: string };

export type LocationSection = SectionWindow & {
  level: "country" | "state" | "pin_prefix" | "pin";
  country?: string | null;
  state?: string | null;
  pinPrefix?: string | null;
  rows: {
    key: string; label: string; orders: number; revenue: number; averageOrderValue: number; share: number;
    change?: number | null; small: boolean;
  }[];
  unrecognised?: { orders: number; samples: { raw: string; orders: number }[] } | null;
  topProducts: { productId: string; name: string; units: number; revenue: number }[];
};
