import { SummaryChange, SummaryKpis } from "./api-types";

/** Step 3's tabs (DashboardSectionsController): date range only. */
export type RangeSectionName = "products" | "customers" | "coupons" | "payments" | "tax";

/** Every Analytics tab; Overview, Sales and Orders also take compare, basis and category. */
export type SectionName = "overview" | "sales" | "orders" | RangeSectionName;

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
