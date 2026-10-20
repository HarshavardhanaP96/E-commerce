import { createApi } from "@reduxjs/toolkit/query/react";
import { CategoryListResponse, CategoryResponse, MessageResponse } from "../../types/api-types";
import { authedBaseQuery } from "./baseQuery";
import { productAPI } from "./productAPI";

/** The category tree behind the storefront filters (admin screens create and edit it). */
export const categoryAPI = createApi({
  reducerPath: "categoryApi",
  baseQuery: authedBaseQuery("category"),
  tagTypes: ["category"],
  endpoints: (builder) => ({
    categoryTree: builder.query<CategoryListResponse, void>({
      query: () => "",
      providesTags: ["category"],
    }),
    newCategory: builder.mutation<CategoryResponse, FormData>({
      query: (body) => ({ url: "new", method: "POST", body }),
      invalidatesTags: ["category"],
    }),
    updateCategory: builder.mutation<CategoryResponse, { id: string; formData: FormData }>({
      query: ({ id, formData }) => ({ url: id, method: "PUT", body: formData }),
      invalidatesTags: ["category"],
      // Products show their category's name, so refresh them too.
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        await queryFulfilled.catch(() => undefined);
        dispatch(productAPI.util.invalidateTags(["product"]));
      },
    }),
    deleteCategory: builder.mutation<MessageResponse, string>({
      query: (id) => ({ url: id, method: "DELETE" }),
      invalidatesTags: ["category"],
    }),
  }),
});

export const {
  useCategoryTreeQuery,
  useNewCategoryMutation,
  useUpdateCategoryMutation,
  useDeleteCategoryMutation,
} = categoryAPI;
