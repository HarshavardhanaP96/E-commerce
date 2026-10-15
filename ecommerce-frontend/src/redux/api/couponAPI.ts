import { createApi } from "@reduxjs/toolkit/query/react";
import { CouponsResponse, DiscountResponse, MessageResponse } from "../../types/api-types";
import { authedBaseQuery } from "./baseQuery";

export const couponAPI = createApi({
  reducerPath: "couponApi",
  baseQuery: authedBaseQuery("payment/"),
  tagTypes: ["coupons"],
  endpoints: (builder) => ({
    discount: builder.query<DiscountResponse, string>({
      query: (coupon) => ({ url: "discount", params: { coupon } }),
    }),
    allCoupons: builder.query<CouponsResponse, void>({
      query: () => "coupon/all",
      providesTags: ["coupons"],
    }),
    newCoupon: builder.mutation<MessageResponse, { coupon: string; amount: number }>({
      query: (body) => ({ url: "coupon/new", method: "POST", body }),
      invalidatesTags: ["coupons"],
    }),
    deleteCoupon: builder.mutation<MessageResponse, string>({
      query: (id) => ({ url: `coupon/${id}`, method: "DELETE" }),
      invalidatesTags: ["coupons"],
    }),
  }),
});

export const { useLazyDiscountQuery, useAllCouponsQuery, useNewCouponMutation, useDeleteCouponMutation } =
  couponAPI;
