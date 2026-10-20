import { createApi } from "@reduxjs/toolkit/query/react";
import {
  AddressesResponse,
  AddressResponse,
  AuthResponse,
  MessageResponse,
  UserResponse,
  UsersResponse,
} from "../../types/api-types";
import { Role, SavedAddressInput } from "../../types/types";
import { authedBaseQuery } from "./baseQuery";

export const userAPI = createApi({
  reducerPath: "userApi",
  baseQuery: authedBaseQuery("user/"),
  tagTypes: ["users", "addresses"],
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
    updateMyProfile: builder.mutation<UserResponse & MessageResponse, { name?: string; phone?: string; locale?: string }>({
      query: (body) => ({ url: "me", method: "PUT", body }),
      invalidatesTags: ["users"],
    }),
    getMyAddresses: builder.query<AddressesResponse, void>({
      query: () => "me/addresses",
      providesTags: ["addresses"],
    }),
    addAddress: builder.mutation<AddressResponse, SavedAddressInput>({
      query: (body) => ({ url: "me/addresses", method: "POST", body }),
      invalidatesTags: ["addresses"],
    }),
    updateAddress: builder.mutation<AddressResponse, { id: string; body: SavedAddressInput }>({
      query: ({ id, body }) => ({ url: `me/addresses/${id}`, method: "PUT", body }),
      invalidatesTags: ["addresses"],
    }),
    deleteAddress: builder.mutation<MessageResponse, string>({
      query: (id) => ({ url: `me/addresses/${id}`, method: "DELETE" }),
      invalidatesTags: ["addresses"],
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
    updateUserStatus: builder.mutation<MessageResponse, { userId: string; status: "ACTIVE" | "SUSPENDED" }>({
      query: ({ userId, status }) => ({ url: userId, method: "PUT", body: { status } }),
      invalidatesTags: ["users"],
    }),
    verifyEmail: builder.mutation<MessageResponse, { token: string }>({
      query: (body) => ({ url: "verify-email", method: "POST", body }),
      invalidatesTags: ["users"],
    }),
    resendVerification: builder.mutation<MessageResponse, void>({
      query: () => ({ url: "me/verify-email/resend", method: "POST" }),
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
  useUpdateMyProfileMutation,
  useGetMyAddressesQuery,
  useLazyGetMyAddressesQuery,
  useAddAddressMutation,
  useUpdateAddressMutation,
  useDeleteAddressMutation,
  useAllUsersQuery,
  useUserDetailsQuery,
  useUpdateUserRoleMutation,
  useUpdateUserStatusMutation,
  useVerifyEmailMutation,
  useResendVerificationMutation,
  useDeleteUserMutation,
} = userAPI;
