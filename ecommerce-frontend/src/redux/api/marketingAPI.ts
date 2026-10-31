import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export type Provider = "google" | "meta";
export type IntegrationStatus = "NOT_CONNECTED" | "ACTIVE";

/** One provider card. `browser` holds that provider's public tag IDs; an unset ID is null. */
export type Integration = {
  provider: Provider;
  status: IntegrationStatus;
  browser: Record<string, string | null>;
  updatedAt: string | null;
};

/** The store's product feed (phase 2); `url` is null while it is off. */
export type Feed = { enabled: boolean; url: string | null };

export type MarketingResponse = { success: boolean; integrations: Integration[]; feed: Feed };
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
  }),
});

const replaceCard = (integration: Integration) =>
  marketingAPI.util.updateQueryData("marketing", undefined, (draft) => {
    const i = draft.integrations.findIndex((c) => c.provider === integration.provider);
    if (i >= 0) draft.integrations[i] = integration;
  });

export const { useMarketingQuery, useSaveIntegrationMutation, useDisconnectIntegrationMutation } = marketingAPI;
