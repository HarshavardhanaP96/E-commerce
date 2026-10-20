import { createApi } from "@reduxjs/toolkit/query/react";
import type { StorefrontConfig } from "../../tenant/tenant";
import { authedBaseQuery } from "./baseQuery";

type Maybe<T> = T | null;

/** The store's own shop rules. A null field means "use the platform default". */
export type StoreSettings = {
  commerce: {
    shippingFee: Maybe<number>; freeShippingAbove: Maybe<number>; defaultTaxRate: Maybe<number>;
    maxCouponAmount: Maybe<number>; minimumAge: Maybe<number>;
  };
  catalog: { pageSize: Maybe<number>; lowStockThreshold: Maybe<number> };
  invoice: { gstin: Maybe<string>; state: Maybe<string> };
  regional: { timeZone: Maybe<string> };
  storefront: StorefrontConfig;
};

/** The value in force for each rule: the store's own, else the platform default. */
export type EffectiveStoreSettings = {
  shippingFee: number; freeShippingAbove: number; defaultTaxRate: number; maxCouponAmount: number;
  minimumAge: number; pageSize: number; lowStockThreshold: number; timeZone: string;
};

export type StoreSettingsResponse = {
  success: boolean;
  settings: StoreSettings;
  effective: EffectiveStoreSettings;
  /** What each rule returns to when the field is left empty. */
  defaults: EffectiveStoreSettings;
};

export const storeAPI = createApi({
  reducerPath: "storeApi",
  baseQuery: authedBaseQuery("store/"),
  tagTypes: ["settings"],
  endpoints: (builder) => ({
    storeSettings: builder.query<StoreSettingsResponse, void>({
      query: () => "settings",
      providesTags: ["settings"],
    }),
    saveStoreSettings: builder.mutation<StoreSettingsResponse, StoreSettings>({
      query: (body) => ({ url: "settings", method: "PUT", body }),
      invalidatesTags: ["settings"],
    }),
  }),
});

export const { useStoreSettingsQuery, useSaveStoreSettingsMutation } = storeAPI;
