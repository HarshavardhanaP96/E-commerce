import { createApi } from "@reduxjs/toolkit/query/react";
import { CouponsResponse, DiscountResponse, MessageResponse } from "../../types/api-types";
import { NewCouponInput } from "../../types/types";
import { authedBaseQuery } from "./baseQuery";

export const couponAPI = createApi({
  reducerPath: "couponApi",
  baseQuery: authedBaseQuery("payment/"),
  tagTypes: ["coupons"],
  endpoints: (builder) => ({
    discount: builder.query<DiscountResponse, { coupon: string; subtotal: number }>({
      query: ({ coupon, subtotal }) => ({ url: "discount", params: { coupon, subtotal } }),
    }),
    allCoupons: builder.query<CouponsResponse, void>({
      query: () => "coupon/all",
      providesTags: ["coupons"],
    }),
    newCoupon: builder.mutation<MessageResponse, NewCouponInput>({
      query: (body) => ({ url: "coupon/new", method: "POST", body }),
      invalidatesTags: ["coupons"],
    }),
    updateCoupon: builder.mutation<MessageResponse, { id: string; body: Partial<NewCouponInput> }>({
      query: ({ id, body }) => ({ url: `coupon/${id}`, method: "PUT", body }),
      invalidatesTags: ["coupons"],
    }),
    deleteCoupon: builder.mutation<MessageResponse, string>({
      query: (id) => ({ url: `coupon/${id}`, method: "DELETE" }),
      invalidatesTags: ["coupons"],
    }),
  }),
});

export const {
  useLazyDiscountQuery,
  useAllCouponsQuery,
  useNewCouponMutation,
  useUpdateCouponMutation,
  useDeleteCouponMutation,
} = couponAPI;
