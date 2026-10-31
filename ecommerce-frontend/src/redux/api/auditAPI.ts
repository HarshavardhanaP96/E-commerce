import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export const AUDIT_ACTIONS = [
  "PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_DELETED",
  "CATEGORY_CREATED", "CATEGORY_UPDATED", "CATEGORY_DELETED",
  "COUPON_CREATED", "COUPON_UPDATED", "COUPON_DELETED",
  "ORDER_PLACED", "ORDER_STATUS_CHANGED", "ORDER_DELETED",
  "USER_REGISTERED", "USER_ROLE_CHANGED", "USER_STATUS_CHANGED", "USER_ERASED", "ACCOUNT_DELETED_SELF",
  "PROFILE_UPDATED", "ADDRESS_ADDED", "ADDRESS_UPDATED", "ADDRESS_DELETED",
  "LOGIN_SUCCEEDED", "LOGIN_FAILED", "LOGIN_BLOCKED_SUSPENDED",
  "PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_COMPLETED",
  "CONSENT_CHANGED",
  "TENANT_CREATED", "TENANT_UPDATED", "TENANT_STATUS_CHANGED",
  "STORE_SETTINGS_UPDATED", "ANALYTICS_EXPORTED",
  "INTEGRATION_UPDATED", "INTEGRATION_DISCONNECTED",
  "FEED_UPDATED", "FEED_TOKEN_ROTATED",
] as const;

export type AuditChange = { field: string; before?: unknown; after?: unknown; redacted: boolean };

export type AuditEntry = {
  _id: string;
  at: string;
  actorId: string | null;
  actorRole: "ADMIN" | "USER" | "ANONYMOUS" | "SYSTEM" | "PLATFORM_ADMIN";
  action: (typeof AUDIT_ACTIONS)[number];
  entityType: string | null;
  entityId: string | null;
  outcome: "SUCCESS" | "FAILURE";
  changes: AuditChange[];
  requestId: string | null;
};

const show = (value: unknown) =>
  value === null || value === undefined ? "—" : Array.isArray(value) ? value.join(", ") : String(value);

/** One line per change: "price: 10 → 12", or "email changed" for personal fields logged without values. */
export const summariseChanges = (changes: AuditChange[]) =>
  changes
    .map((c) => (c.redacted ? `${c.field} changed` : `${c.field}: ${show(c.before)} → ${show(c.after)}`))
    .join("; ");

export type AuditFilters = {
  entityType?: string;
  entityId?: string;
  actorId?: string;
  action?: string;
  outcome?: string;
  from?: string;
  to?: string;
  page: number;
  limit: number;
};

export type AuditResponse = { success: boolean; entries: AuditEntry[]; totalPage: number; total: number };

export const auditAPI = createApi({
  reducerPath: "auditApi",
  baseQuery: authedBaseQuery("admin/"),
  endpoints: (builder) => ({
    auditLog: builder.query<AuditResponse, AuditFilters>({
      query: (filters) => ({
        url: "audit",
        // Empty filters are left out rather than sent as blank values.
        params: Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== "" && v !== undefined)),
      }),
    }),
  }),
});

export const { useAuditLogQuery } = auditAPI;
