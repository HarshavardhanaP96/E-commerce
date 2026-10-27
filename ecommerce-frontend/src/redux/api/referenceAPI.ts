import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export type IndianState = { code: string; name: string; unionTerritory: boolean };

export const referenceAPI = createApi({
  reducerPath: "referenceApi",
  baseQuery: authedBaseQuery("reference/"),
  endpoints: (builder) => ({
    indianStates: builder.query<{ success: boolean; states: IndianState[] }, void>({ query: () => "indian-states" }),
  }),
});

export const { useIndianStatesQuery } = referenceAPI;
