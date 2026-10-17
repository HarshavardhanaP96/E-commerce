import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export type ConsentPurpose = "terms" | "privacy" | "marketing_email";

export type ConsentResponse = {
  success: boolean;
  consents: Partial<Record<ConsentPurpose, { granted: boolean; version: string; at: string }>>;
  /** Required policies the user still has to accept at the current version. */
  pending: ConsentPurpose[];
  history: { purpose: ConsentPurpose; granted: boolean; version: string; source: string; at: string }[];
};

export const consentAPI = createApi({
  reducerPath: "consentApi",
  baseQuery: authedBaseQuery(""),
  tagTypes: ["consent"],
  endpoints: (builder) => ({
    /** The arg is the signed-in user's id: it only keys the cache so accounts never share an entry. */
    myConsent: builder.query<ConsentResponse, string>({
      query: () => "consent/me",
      providesTags: ["consent"],
    }),
    decideConsent: builder.mutation<
      ConsentResponse,
      { purpose: ConsentPurpose; granted: boolean; source: "reprompt" | "settings" }
    >({
      query: (body) => ({ url: "consent", method: "POST", body }),
      invalidatesTags: ["consent"],
    }),
  }),
});

export const { useMyConsentQuery, useLazyMyConsentQuery, useDecideConsentMutation } = consentAPI;
