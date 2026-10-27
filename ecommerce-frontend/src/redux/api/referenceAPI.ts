import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

/** `aliases`: the other spellings and older codes the server also accepts. */
export type IndianState = { code: string; name: string; unionTerritory: boolean; aliases: string[] };

export const referenceAPI = createApi({
  reducerPath: "referenceApi",
  baseQuery: authedBaseQuery("reference/"),
  endpoints: (builder) => ({
    indianStates: builder.query<{ success: boolean; states: IndianState[] }, void>({ query: () => "indian-states" }),
  }),
});

export const { useIndianStatesQuery } = referenceAPI;
