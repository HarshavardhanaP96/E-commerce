import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";
import { server } from "../config";
import type { AuditEntry } from "./auditAPI";

export const PLATFORM_TOKEN_KEY = "platformToken";

export type PlatformTenant = {
  id: string;
  slug: string;
  name: string;
  status: "ACTIVE" | "SUSPENDED" | "SCHEDULED_FOR_DELETION" | "DELETED";
  /** When a store scheduled for deletion will be purged. */
  deletionDueAt?: string | null;
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
  /** The store's own limits; null follows the platform default. */
  limits?: { products?: number | null; uploadBytes?: number | null; requestsPerMinute?: number | null };
  createdAt?: string;
};

export type NewTenantInput = {
  slug: string;
  name: string;
  domains: string[];
  admin?: { name: string; email: string; password: string };
};

export type PlatformAuditEntry = AuditEntry & { tenantId: string | null };
export type PlatformAuditFilters = { tenantId?: string; action?: string; page: number };
type PlatformAuditResponse = { success: boolean; entries: PlatformAuditEntry[]; totalPage: number };

type TenantsResponse = { success: boolean; tenants: PlatformTenant[] };
type TenantResponse = { success: boolean; message: string; tenant: PlatformTenant };

/**
 * Platform administration. Uses its own token (never the store session's) because platform credentials are
 * separate from any store's customers and admins.
 */
/** Downloads a store's export ZIP (a plain fetch: the response is a file, not JSON). */
export const downloadTenantExport = async (tenant: { id: string; slug: string }) => {
  const res = await fetch(`${server}/api/v1/platform/tenants/${tenant.id}/export`, {
    headers: { Authorization: `Bearer ${localStorage.getItem(PLATFORM_TOKEN_KEY) ?? ""}` },
  });
  if (!res.ok) throw new Error(`Export failed (${res.status})`);
  const url = URL.createObjectURL(await res.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = `${tenant.slug}-export.zip`;
  link.click();
  URL.revokeObjectURL(url);
};

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
  tagTypes: ["tenants", "audit"],
  endpoints: (builder) => ({
    platformLogin: builder.mutation<{ success: boolean; message: string; token: string }, { email: string; password: string }>({
      query: (body) => ({ url: "login", method: "POST", body }),
    }),
    tenants: builder.query<TenantsResponse, void>({ query: () => "tenants", providesTags: ["tenants"] }),
    createTenant: builder.mutation<TenantResponse, NewTenantInput>({
      query: (body) => ({ url: "tenants", method: "POST", body }),
      invalidatesTags: ["tenants", "audit"],
    }),
    updateTenant: builder.mutation<TenantResponse, { id: string; body: Record<string, unknown> }>({
      query: ({ id, body }) => ({ url: `tenants/${id}`, method: "PUT", body }),
      invalidatesTags: ["tenants", "audit"],
    }),
    setTenantStatus: builder.mutation<TenantResponse, { id: string; action: "suspend" | "activate" | "offboard" | "restore" }>({
      query: ({ id, action }) => ({ url: `tenants/${id}/${action}`, method: "POST" }),
      invalidatesTags: ["tenants", "audit"],
    }),
    /** Every store's audit log, read-only. */
    platformAudit: builder.query<PlatformAuditResponse, PlatformAuditFilters>({
      query: (filters) => ({
        url: "audit",
        params: Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== "" && v !== undefined)),
      }),
      providesTags: ["audit"],
    }),
  }),
});

export const {
  usePlatformLoginMutation,
  useTenantsQuery,
  useCreateTenantMutation,
  useUpdateTenantMutation,
  useSetTenantStatusMutation,
  usePlatformAuditQuery,
} = platformAPI;
