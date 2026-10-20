import { createApi } from "@reduxjs/toolkit/query/react";
import { BarResponse, LineResponse, PieResponse, StatsResponse, SummaryResponse, VariantSalesResponse } from "../../types/api-types";
import { DashboardScope, SummaryParams } from "../../types/types";
import type { CouponsSection, CustomersSection, PaymentsSection, ProductsSection, TaxSection } from "../../types/sections";
import type { DateRange } from "../../utils/dateRange";
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
    getProductsSection: builder.query<{ products: ProductsSection }, DateRange>({
      query: (params) => ({ url: "products", params }),
    }),
    getCustomersSection: builder.query<{ customers: CustomersSection }, DateRange>({
      query: (params) => ({ url: "customers", params }),
    }),
    getCouponsSection: builder.query<{ coupons: CouponsSection }, DateRange>({
      query: (params) => ({ url: "coupons", params }),
    }),
    getPaymentsSection: builder.query<{ payments: PaymentsSection }, DateRange>({
      query: (params) => ({ url: "payments", params }),
    }),
    getTaxSection: builder.query<{ tax: TaxSection }, DateRange>({
      query: (params) => ({ url: "tax", params }),
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
  useGetProductsSectionQuery,
  useGetCustomersSectionQuery,
  useGetCouponsSectionQuery,
  useGetPaymentsSectionQuery,
  useGetTaxSectionQuery,
} = dashboardAPI;
