import { createApi } from "@reduxjs/toolkit/query/react";
import {
  CheckoutRequest,
  CheckoutResponse,
  ConfirmOrderRequest,
  ConfirmOrderResponse,
  InvoiceResponse,
  MessageResponse,
  OrderResponse,
  OrdersResponse,
  QuoteRequest,
  QuoteResponse,
  RefundResponse,
} from "../../types/api-types";
import { authedBaseQuery } from "./baseQuery";
import { clean } from "./userAPI";
import { productAPI } from "./productAPI";

export const orderAPI = createApi({
  reducerPath: "orderApi",
  baseQuery: authedBaseQuery("order/"),
  tagTypes: ["orders"],
  endpoints: (builder) => ({
    checkout: builder.mutation<CheckoutResponse, CheckoutRequest>({
      query: (body) => ({ url: "checkout", method: "POST", body }),
    }),
    confirmOrder: builder.mutation<ConfirmOrderResponse, ConfirmOrderRequest>({
      query: (body) => ({ url: "new", method: "POST", body }),
      invalidatesTags: ["orders"],
      // Stock changed: refresh catalogue data too.
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        await queryFulfilled.catch(() => undefined);
        dispatch(productAPI.util.invalidateTags(["product"]));
      },
    }),
    /** Prices a cart exactly as checkout will, including coupon scope; works without signing in. */
    quote: builder.mutation<QuoteResponse, QuoteRequest>({
      query: (body) => ({ url: "quote", method: "POST", body }),
    }),
    myOrders: builder.query<OrdersResponse, void>({
      query: () => "my",
      providesTags: ["orders"],
    }),
    allOrders: builder.query<OrdersResponse, { status?: string }>({
      query: (params) => ({ url: "all", params: clean(params) }),
      providesTags: ["orders"],
    }),
    orderDetails: builder.query<OrderResponse, string>({
      query: (id) => id,
      providesTags: ["orders"],
    }),
    processOrder: builder.mutation<MessageResponse, string>({
      query: (id) => ({ url: id, method: "PUT" }),
      invalidatesTags: ["orders"],
    }),
    orderInvoice: builder.query<InvoiceResponse, string>({
      query: (id) => `${id}/invoice`,
      providesTags: ["orders"],
    }),
    refundOrder: builder.mutation<RefundResponse, { id: string; amount?: number; reason: string }>({
      query: ({ id, ...body }) => ({ url: `${id}/refund`, method: "POST", body }),
      invalidatesTags: ["orders"],
    }),
    deleteOrder: builder.mutation<MessageResponse, string>({
      query: (id) => ({ url: id, method: "DELETE" }),
      invalidatesTags: ["orders"],
    }),
  }),
});

export const {
  useCheckoutMutation,
  useConfirmOrderMutation,
  useQuoteMutation,
  useMyOrdersQuery,
  useLazyMyOrdersQuery,
  useAllOrdersQuery,
  useOrderDetailsQuery,
  useOrderInvoiceQuery,
  useRefundOrderMutation,
  useProcessOrderMutation,
  useDeleteOrderMutation,
} = orderAPI;
