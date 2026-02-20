import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react";
import { server } from "../config";
import { MessageResponse, UserResponse, DeleteUserRequest, AuthResponse } from "../../types/api-types";
import axios from "axios";

// Helper to get token from localStorage
const getToken = () => localStorage.getItem("token");

export const userAPI = createApi({
  reducerPath: "userApi",
  baseQuery: fetchBaseQuery({ 
    baseUrl: `${server}/api/v1/user/`,
    prepareHeaders: (headers) => {
      const token = getToken();
      if (token) {
        headers.set("Authorization", `Bearer ${token}`);
      }
      return headers;
    }
  }),
  tagTypes: ["users"],
  endpoints: (builder) => ({
    register: builder.mutation<AuthResponse, FormData>({
      query: (user) => ({
        url: "register",
        method: "POST",
        body: user,
      }),
      invalidatesTags: ["users"],
    }),
    login: builder.mutation<AuthResponse, { email: string; password: string }>({
      query: (credentials) => ({
        url: "login",
        method: "POST",
        body: credentials,
      }),
      invalidatesTags: ["users"],
    }),
    getMyProfile: builder.query<UserResponse, void>({
      query: () => "me",
      providesTags: ["users"],
    }),
    deleteUser: builder.mutation<MessageResponse, DeleteUserRequest>({
      query: ({ userId }) => ({
        url: `${userId}`,
        method: "DELETE",
      }),
      invalidatesTags: ["users"],
    }),
    allUsers: builder.query<UserResponse, void>({
      query: () => "all",
      providesTags: ["users"],
    }),
    resetPassword: builder.mutation<MessageResponse, { email: string; password: string }>({
      query: (data) => ({
        url: "reset-password",
        method: "POST",
        body: data,
      }),
    }),
  }),
});

export const getUser = async (token: string) => {
  try {
    const { data }: { data: UserResponse } = await axios.get(
      `${server}/api/v1/user/me`,
      {
        headers: { Authorization: `Bearer ${token}` },
      }
    );

    return data;
  } catch (error) {
    throw error;
  }
};

export const { 
  useRegisterMutation, 
  useLoginMutation, 
  useGetMyProfileQuery,
  useAllUsersQuery, 
  useDeleteUserMutation,
  useResetPasswordMutation
} = userAPI;
