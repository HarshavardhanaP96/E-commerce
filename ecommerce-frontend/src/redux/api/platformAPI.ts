import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";
import { server } from "../config";

export const PLATFORM_TOKEN_KEY = "platformToken";

export type PlatformTenant = {
  id: string;
  slug: string;
  name: string;
  status: "ACTIVE" | "SUSPENDED";
  domains: string[];
  theme: Record<string, string>;
  locale?: string;
  currency?: string;
  legal?: Record<string, string | null>;
  payment?: Record<string, string | null>;
  mail?: Record<string, string | null>;
  storefront?: Record<string, string | null>;
  /** The store's own feature switches; a missing key follows the platform default. */
  features?: Record<string, boolean>;
  createdAt?: string;
};

export type NewTenantInput = {
  slug: string;
  name: string;
  domains: string[];
  admin?: { name: string; email: string; password: string };
};

type TenantsResponse = { success: boolean; tenants: PlatformTenant[] };
type TenantResponse = { success: boolean; message: string; tenant: PlatformTenant };

/**
 * Platform administration. Uses its own token (never the store session's) because platform credentials are
 * separate from any store's customers and admins.
 */
export const platformAPI = createApi({
  reducerPath: "platformApi",
  baseQuery: fetchBaseQuery({
    baseUrl: `${server}/api/v1/platform/`,
    prepareHeaders: (headers) => {
      const token = localStorage.getItem(PLATFORM_TOKEN_KEY);
      if (token) headers.set("Authorization", `Bearer ${token}`);
      return headers;
    },
  }),
  tagTypes: ["tenants"],
  endpoints: (builder) => ({
    platformLogin: builder.mutation<{ success: boolean; message: string; token: string }, { email: string; password: string }>({
      query: (body) => ({ url: "login", method: "POST", body }),
    }),
    tenants: builder.query<TenantsResponse, void>({ query: () => "tenants", providesTags: ["tenants"] }),
    createTenant: builder.mutation<TenantResponse, NewTenantInput>({
      query: (body) => ({ url: "tenants", method: "POST", body }),
      invalidatesTags: ["tenants"],
    }),
    updateTenant: builder.mutation<TenantResponse, { id: string; body: Record<string, unknown> }>({
      query: ({ id, body }) => ({ url: `tenants/${id}`, method: "PUT", body }),
      invalidatesTags: ["tenants"],
    }),
    setTenantStatus: builder.mutation<TenantResponse, { id: string; action: "suspend" | "activate" }>({
      query: ({ id, action }) => ({ url: `tenants/${id}/${action}`, method: "POST" }),
      invalidatesTags: ["tenants"],
    }),
  }),
});

export const {
  usePlatformLoginMutation,
  useTenantsQuery,
  useCreateTenantMutation,
  useUpdateTenantMutation,
  useSetTenantStatusMutation,
} = platformAPI;
