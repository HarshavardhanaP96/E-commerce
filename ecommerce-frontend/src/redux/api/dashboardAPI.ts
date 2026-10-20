import { createApi } from "@reduxjs/toolkit/query/react";
import { BarResponse, LineResponse, PieResponse, StatsResponse, SummaryResponse, VariantSalesResponse } from "../../types/api-types";
import { OrdersSection, OverviewSection, SalesSection, SectionParams, SectionResponse } from "../../types/analytics";
import { DashboardScope, SummaryParams } from "../../types/types";
import { authedBaseQuery } from "./baseQuery";
import { clean } from "./userAPI";

export const dashboardAPI = createApi({
  reducerPath: "dashboardApi",
  baseQuery: authedBaseQuery("dashboard/"),
  keepUnusedDataFor: 0,
  endpoints: (builder) => ({
    getSummary: builder.query<SummaryResponse, SummaryParams>({
      query: (params) => ({ url: "summary", params: clean(params) }),
    }),
    getDashboardStats: builder.query<StatsResponse, DashboardScope>({
      query: (params) => ({ url: "stats", params: clean(params) }),
    }),
    getPieChart: builder.query<PieResponse, DashboardScope>({
      query: (params) => ({ url: "pie", params: clean(params) }),
    }),
    getBarChart: builder.query<BarResponse, DashboardScope>({
      query: (params) => ({ url: "bar", params: clean(params) }),
    }),
    getLineChart: builder.query<LineResponse, DashboardScope>({
      query: (params) => ({ url: "line", params: clean(params) }),
    }),
    getOverview: builder.query<SectionResponse<OverviewSection>, SectionParams>({
      query: (params) => ({ url: "overview", params: clean(params) }),
    }),
    getSales: builder.query<SectionResponse<SalesSection>, SectionParams>({
      query: (params) => ({ url: "sales", params: clean(params) }),
    }),
    getOrdersSection: builder.query<SectionResponse<OrdersSection>, SectionParams>({
      query: (params) => ({ url: "orders", params: clean(params) }),
    }),
    getVariantSales: builder.query<VariantSalesResponse, string>({
      query: (productId) => `products/${productId}/variants`,
    }),
  }),
});

export const {
  useGetSummaryQuery,
  useGetDashboardStatsQuery,
  useGetPieChartQuery,
  useGetBarChartQuery,
  useGetLineChartQuery,
  useGetVariantSalesQuery,
  useGetOverviewQuery,
  useGetSalesQuery,
  useGetOrdersSectionQuery,
} = dashboardAPI;
