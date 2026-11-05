import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";
import { server } from "../config";
import type { AuditEntry } from "./auditAPI";
import type { StoreSettings, StoreTypeKey } from "./storeAPI";

export type { StoreTypeKey } from "./storeAPI";

export const PLATFORM_TOKEN_KEY = "platformToken";

export type PlatformTenant = {
  id: string;
  slug: string;
  name: string;
  status: "TRIAL" | "ACTIVE" | "SUSPENDED" | "CANCELLED" | "SCHEDULED_FOR_DELETION" | "DELETED";
  /** Set at onboarding; a label afterwards. */
  type: StoreTypeKey;
  /** When a trial store is suspended unless activated. */
  trialEndsAt?: string | null;
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
  trial?: boolean;
  type?: StoreTypeKey;
  admin?: { name: string; email: string; password: string };
};

export type PlatformAuditEntry = AuditEntry & { tenantId: string | null };
export type PlatformAuditFilters = { tenantId?: string; action?: string; page: number };
type PlatformAuditResponse = { success: boolean; entries: PlatformAuditEntry[]; totalPage: number };

export type OwnerSettings = Omit<StoreSettings, "storefront">;
export type StoreTypeInfo = {
  key: StoreTypeKey;
  label: string;
  description: string;
  features: Record<string, boolean>;
  settings: OwnerSettings;
};
export type PlatformDefaults = {
  features: Record<string, boolean>;
  limits: { products: number; uploadBytes: number; requestsPerMinute: number };
};
export type TenantDetails = {
  success: boolean;
  tenant: PlatformTenant;
  settings: OwnerSettings;
  usage: { products: number; uploadBytes: number } | null;
  defaults: PlatformDefaults;
};

export const PLATFORM_EMAIL_KEY = "sixpack.platform.email";
/** Fired when the platform session ends (sign-out or a 401), so the console returns to sign-in. */
export const PLATFORM_SIGNED_OUT = "sixpack:platform-signed-out";

export const signOutPlatform = () => {
  localStorage.removeItem(PLATFORM_TOKEN_KEY);
  localStorage.removeItem(PLATFORM_EMAIL_KEY);
  window.dispatchEvent(new Event(PLATFORM_SIGNED_OUT));
};

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

const rawPlatformQuery = fetchBaseQuery({
  baseUrl: `${server}/api/v1/platform/`,
  prepareHeaders: (headers) => {
    const token = localStorage.getItem(PLATFORM_TOKEN_KEY);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    return headers;
  },
});

/** Any 401 ends the platform session, wherever in the console it happens. */
const platformBaseQuery: typeof rawPlatformQuery = async (args, api, extra) => {
  const result = await rawPlatformQuery(args, api, extra);
  if (result.error?.status === 401 && localStorage.getItem(PLATFORM_TOKEN_KEY)) signOutPlatform();
  return result;
};

export const platformAPI = createApi({
  reducerPath: "platformApi",
  baseQuery: platformBaseQuery,
  tagTypes: ["tenants", "audit", "tenant", "storeTypes"],
  endpoints: (builder) => ({
    platformLogin: builder.mutation<{ success: boolean; message: string; token: string }, { email: string; password: string }>({
      query: (body) => ({ url: "login", method: "POST", body }),
    }),
    tenants: builder.query<TenantsResponse, void>({ query: () => "tenants", providesTags: ["tenants"] }),
    tenant: builder.query<TenantDetails, string>({
      query: (id) => `tenants/${id}`,
      providesTags: (_r, _e, id) => [{ type: "tenant", id }],
    }),
    storeTypes: builder.query<{ success: boolean; types: StoreTypeInfo[] }, void>({
      query: () => "store-types",
      providesTags: ["storeTypes"],
      keepUnusedDataFor: 3600, // presets change only with a release
    }),
    createTenant: builder.mutation<TenantResponse, NewTenantInput>({
      query: (body) => ({ url: "tenants", method: "POST", body }),
      invalidatesTags: ["tenants", "audit"],
    }),
    updateTenant: builder.mutation<TenantResponse, { id: string; body: Record<string, unknown> }>({
      query: ({ id, body }) => ({ url: `tenants/${id}`, method: "PUT", body }),
      invalidatesTags: (_r, _e, { id }) => ["tenants", "audit", { type: "tenant", id }],
    }),
    setTenantStatus: builder.mutation<TenantResponse, { id: string; action: "suspend" | "activate" | "cancel" | "offboard" | "restore" }>({
      query: ({ id, action }) => ({ url: `tenants/${id}/${action}`, method: "POST" }),
      invalidatesTags: (_r, _e, { id }) => ["tenants", "audit", { type: "tenant", id }],
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
  useTenantQuery,
  useStoreTypesQuery,
  useCreateTenantMutation,
  useUpdateTenantMutation,
  useSetTenantStatusMutation,
  usePlatformAuditQuery,
} = platformAPI;
