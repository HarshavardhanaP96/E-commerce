import { createApi } from "@reduxjs/toolkit/query/react";
import {
  AuthResponse,
  MessageResponse,
  UserResponse,
  UsersResponse,
} from "../../types/api-types";
import { Role } from "../../types/types";
import { authedBaseQuery } from "./baseQuery";

export const userAPI = createApi({
  reducerPath: "userApi",
  baseQuery: authedBaseQuery("user/"),
  tagTypes: ["users"],
  endpoints: (builder) => ({
    register: builder.mutation<AuthResponse, FormData>({
      query: (body) => ({ url: "register", method: "POST", body }),
      invalidatesTags: ["users"],
    }),
    login: builder.mutation<AuthResponse, { email: string; password: string }>({
      query: (body) => ({ url: "login", method: "POST", body }),
      invalidatesTags: ["users"],
    }),
    forgotPassword: builder.mutation<MessageResponse, { email: string }>({
      query: (body) => ({ url: "forgot-password", method: "POST", body }),
    }),
    resetPassword: builder.mutation<MessageResponse, { token: string; password: string }>({
      query: (body) => ({ url: "reset-password", method: "POST", body }),
    }),
    deleteMyAccount: builder.mutation<MessageResponse, { password: string }>({
      query: (body) => ({ url: "me", method: "DELETE", body }),
    }),
    getMyProfile: builder.query<UserResponse, void>({
      query: () => "me",
      providesTags: ["users"],
    }),
    allUsers: builder.query<UsersResponse, { role?: string; gender?: string }>({
      query: (params) => ({ url: "all", params: clean(params) }),
      providesTags: ["users"],
    }),
    userDetails: builder.query<UserResponse, string>({
      query: (id) => id,
      providesTags: ["users"],
    }),
    updateUserRole: builder.mutation<MessageResponse, { userId: string; role: Role }>({
      query: ({ userId, role }) => ({ url: userId, method: "PUT", body: { role } }),
      invalidatesTags: ["users"],
    }),
    deleteUser: builder.mutation<MessageResponse, string>({
      query: (userId) => ({ url: userId, method: "DELETE" }),
      invalidatesTags: ["users"],
    }),
  }),
});

/** Drops empty filter values so they are not sent as `?role=`. */
export const clean = <T extends Record<string, unknown>>(params: T) =>
  Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== "")) as Partial<T>;

export const {
  useRegisterMutation,
  useLoginMutation,
  useForgotPasswordMutation,
  useResetPasswordMutation,
  useDeleteMyAccountMutation,
  useGetMyProfileQuery,
  useLazyGetMyProfileQuery,
  useAllUsersQuery,
  useUserDetailsQuery,
  useUpdateUserRoleMutation,
  useDeleteUserMutation,
} = userAPI;
