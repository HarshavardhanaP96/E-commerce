import { createApi } from "@reduxjs/toolkit/query/react";
import { BarResponse, LineResponse, PieResponse, StatsResponse } from "../../types/api-types";
import { DashboardScope } from "../../types/types";
import { authedBaseQuery } from "./baseQuery";
import { clean } from "./userAPI";

export const dashboardAPI = createApi({
  reducerPath: "dashboardApi",
  baseQuery: authedBaseQuery("dashboard/"),
  keepUnusedDataFor: 0,
  endpoints: (builder) => ({
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
  }),
});

export const {
  useGetDashboardStatsQuery,
  useGetPieChartQuery,
  useGetBarChartQuery,
  useGetLineChartQuery,
} = dashboardAPI;
