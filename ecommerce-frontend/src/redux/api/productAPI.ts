import { createApi } from "@reduxjs/toolkit/query/react";
import {
  CategoriesResponse,
  OptionFacetsResponse,
  MessageResponse,
  ProductResponse,
  ProductSavedResponse,
  VariantsRequest,
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
    optionFacets: builder.query<OptionFacetsResponse, void>({
      query: () => "options",
      providesTags: ["product"],
    }),
    searchProducts: builder.query<SearchProductsResponse, SearchProductsRequest>({
      query: ({ price, search, sort, category, page, options }) => {
        const params = new URLSearchParams();
        Object.entries(clean({ search, sort, category, page, price: price || undefined })).forEach(([k, v]) =>
          params.set(k, String(v))
        );
        options?.forEach((o) => params.append("option", o));
        return `all?${params}`;
      },
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
    replaceVariants: builder.mutation<ProductSavedResponse, { productId: string; body: VariantsRequest }>({
      query: ({ productId, body }) => ({ url: `${productId}/variants`, method: "PUT", body }),
      invalidatesTags: ["product"],
    }),
    adjustVariantStock: builder.mutation<
      ProductSavedResponse,
      { productId: string; variantId: string; set: number }
    >({
      query: ({ productId, variantId, set }) => ({
        url: `${productId}/variants/${variantId}/stock`,
        method: "PATCH",
        body: { set },
      }),
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
  useOptionFacetsQuery,
  useSearchProductsQuery,
  useProductDetailsQuery,
  useNewProductMutation,
  useUpdateProductMutation,
  useReplaceVariantsMutation,
  useAdjustVariantStockMutation,
  useDeleteProductMutation,
} = productAPI;
