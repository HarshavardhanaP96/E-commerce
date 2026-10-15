import { createApi } from "@reduxjs/toolkit/query/react";
import {
  CategoriesResponse,
  MessageResponse,
  ProductResponse,
  ProductsResponse,
  SearchProductsRequest,
  SearchProductsResponse,
} from "../../types/api-types";
import { authedBaseQuery } from "./baseQuery";
import { clean } from "./userAPI";

export const productAPI = createApi({
  reducerPath: "productApi",
  baseQuery: authedBaseQuery("product/"),
  tagTypes: ["product"],
  endpoints: (builder) => ({
    latestProducts: builder.query<ProductsResponse, void>({
      query: () => "latest",
      providesTags: ["product"],
    }),
    adminProducts: builder.query<ProductsResponse, void>({
      query: () => "admin-products",
      providesTags: ["product"],
    }),
    categories: builder.query<CategoriesResponse, void>({
      query: () => "categories",
      providesTags: ["product"],
    }),
    searchProducts: builder.query<SearchProductsResponse, SearchProductsRequest>({
      query: ({ price, search, sort, category, page }) => ({
        url: "all",
        params: clean({ search, sort, category, page, price: price || undefined }),
      }),
      providesTags: ["product"],
    }),
    productDetails: builder.query<ProductResponse, string>({
      query: (id) => id,
      providesTags: ["product"],
    }),
    newProduct: builder.mutation<MessageResponse, FormData>({
      query: (body) => ({ url: "new", method: "POST", body }),
      invalidatesTags: ["product"],
    }),
    updateProduct: builder.mutation<MessageResponse, { productId: string; formData: FormData }>({
      query: ({ productId, formData }) => ({ url: productId, method: "PUT", body: formData }),
      invalidatesTags: ["product"],
    }),
    deleteProduct: builder.mutation<MessageResponse, string>({
      query: (productId) => ({ url: productId, method: "DELETE" }),
      invalidatesTags: ["product"],
    }),
  }),
});

export const {
  useLatestProductsQuery,
  useAdminProductsQuery,
  useCategoriesQuery,
  useSearchProductsQuery,
  useProductDetailsQuery,
  useNewProductMutation,
  useUpdateProductMutation,
  useDeleteProductMutation,
} = productAPI;
