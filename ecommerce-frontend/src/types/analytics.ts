import { SummaryChange, SummaryKpis } from "./api-types";

export type SectionName = "overview" | "sales" | "orders" | "stock" | "customers" | "demand" | "location";

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
  byCategory: { category: string; revenue: number; units: number; share: number; change?: number | null }[];
  breakdown?: {
    charged: number; discount: number; tax: number; shipping: number; refunds: number; net: number;
    couponOrders: number; couponOrderShare: number;
  } | null;
  byPaymentMethod?: { method: string; orders: number; revenue: number; share: number }[] | null;
};
