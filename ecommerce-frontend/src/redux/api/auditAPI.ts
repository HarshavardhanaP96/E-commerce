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
] as const;

export type AuditChange = { field: string; before?: unknown; after?: unknown; redacted: boolean };

export type AuditEntry = {
  _id: string;
  at: string;
  actorId: string | null;
  actorRole: "ADMIN" | "USER" | "ANONYMOUS" | "SYSTEM";
  action: (typeof AUDIT_ACTIONS)[number];
  entityType: string | null;
  entityId: string | null;
  outcome: "SUCCESS" | "FAILURE";
  changes: AuditChange[];
  requestId: string | null;
};

export type AuditFilters = {
  entityType?: string;
  action?: string;
  outcome?: string;
  from?: string;
  to?: string;
  page: number;
};

export type AuditResponse = { success: boolean; entries: AuditEntry[]; totalPage: number };

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
