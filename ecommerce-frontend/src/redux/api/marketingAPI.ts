import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export type Provider = "google" | "meta";
export type IntegrationStatus = "NOT_CONNECTED" | "ACTIVE" | "ERROR";
export type ServerSettings = { enabled: boolean; hasSecret: boolean; testEventCode: string | null; available: boolean };
export type DeliveryStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "SKIPPED";
export type Delivery = {
  eventId: string; orderNumber: string | null; eventName: string; status: DeliveryStatus; attempts: number;
  createdAt: string; updatedAt: string; nextAttemptAt: string | null; error: { code: string; message: string } | null;
};
export type LastError = { at: string; code: string; message: string };

/**
 * One provider card. `browser` holds that provider's public tag IDs; an unset ID is null. The server-event keys
 * are optional so a phase-1 server's cards still type-check.
 */
export type Integration = {
  provider: Provider;
  status: IntegrationStatus;
  browser: Record<string, string | null>;
  updatedAt: string | null;
  server?: ServerSettings;
  lastError?: LastError | null;
  lastSuccessAt?: string | null;
  deliveries?: Delivery[];
};

/** `secret` is write-only: omit it to keep the saved one. */
export type ServerChange = { enabled: boolean; secret?: string; testEventCode?: string | null };
export type TestVerdict = { ok: boolean; code: string | null; message: string };
export type DeliveriesResponse = { success: boolean; deliveries: Delivery[]; page: number; totalPage: number };

export type MarketingResponse = { success: boolean; integrations: Integration[] };
export type IntegrationResponse = { success: boolean; integration: Integration };

/**
 * Admin → Marketing. First load is one GET with every card; save and disconnect each make one call and put
 * the returned card into the cached list instead of refetching it (call budget, marketing spec §7).
 */
export const marketingAPI = createApi({
  reducerPath: "marketingApi",
  baseQuery: authedBaseQuery("admin/marketing"),
  endpoints: (builder) => ({
    marketing: builder.query<MarketingResponse, void>({
      query: () => "",
    }),
    saveIntegration: builder.mutation<IntegrationResponse, { provider: Provider; browser: Record<string, string | null> }>({
      query: ({ provider, browser }) => ({ url: provider, method: "PUT", body: { browser } }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(replaceCard(data.integration));
        } catch {
          // the page shows the error from the mutation result
        }
      },
    }),
    disconnectIntegration: builder.mutation<IntegrationResponse, Provider>({
      query: (provider) => ({ url: provider, method: "DELETE" }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled;
          dispatch(replaceCard(data.integration));
        } catch {
          // the page shows the error from the mutation result
        }
      },
    }),
    saveServerSettings: builder.mutation<IntegrationResponse, { provider: Provider; server: ServerChange }>({
      query: ({ provider, server }) => ({ url: provider, method: "PUT", body: { server } }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          dispatch(replaceCard((await queryFulfilled).data.integration));
        } catch {
          // the section shows the error from the mutation result
        }
      },
    }),
    sendTestEvent: builder.mutation<{ success: boolean; test: TestVerdict }, Provider>({
      query: (provider) => ({ url: `${provider}/test`, method: "POST" }),
    }),
    deliveries: builder.query<DeliveriesResponse, { provider: Provider; page: number }>({
      query: ({ provider, page }) => `${provider}/deliveries?page=${page}`,
    }),
  }),
});

const replaceCard = (integration: Integration) =>
  marketingAPI.util.updateQueryData("marketing", undefined, (draft) => {
    const i = draft.integrations.findIndex((c) => c.provider === integration.provider);
    if (i >= 0) draft.integrations[i] = integration;
  });

export const {
  useMarketingQuery, useSaveIntegrationMutation, useDisconnectIntegrationMutation,
  useSaveServerSettingsMutation, useSendTestEventMutation, useLazyDeliveriesQuery,
} = marketingAPI;
