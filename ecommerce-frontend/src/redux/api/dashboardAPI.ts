import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";
import { server } from "../config";
import {
  BarResponse,
  LineResponse,
  PieResponse,
  StatsResponse,
} from "../../types/api-types";

// Helper to get token from localStorage
const getToken = () => localStorage.getItem("token");

export const dashboardAPI = createApi({
  reducerPath: "dashboardApi",
  baseQuery: fetchBaseQuery({
    baseUrl: `${server}/api/v1/dashboard/`,
    prepareHeaders: (headers) => {
        const token = getToken();
        if (token) {
          headers.set("Authorization", `Bearer ${token}`);
        }
        return headers;
    },
  }),
  tagTypes: ["stats"],
  endpoints: (builder) => ({
    getDashboardStats: builder.query<StatsResponse, { productId?: string; category?: string; userId?: string }>({
      query: (params) => ({
        url: "stats",
        params,
      }),
      keepUnusedDataFor: 0,
    }),
    getPieChart: builder.query<PieResponse, { productId?: string; category?: string; userId?: string }>({
      query: (params) => ({
        url: "pie",
        params,
      }),
      keepUnusedDataFor: 0,
    }),
    getBarChart: builder.query<BarResponse, { productId?: string; category?: string; userId?: string }>({
      query: (params) => ({
        url: "bar",
        params,
      }),
      keepUnusedDataFor: 0,
    }),
    getLineChart: builder.query<LineResponse, { productId?: string; category?: string; userId?: string }>({
      query: (params) => ({
        url: "line",
        params,
      }),
      keepUnusedDataFor: 0,
    }),
  }),
});

export const {
  useGetBarChartQuery,
  useGetDashboardStatsQuery,
  useGetLineChartQuery,
  useGetPieChartQuery,
} = dashboardAPI;
