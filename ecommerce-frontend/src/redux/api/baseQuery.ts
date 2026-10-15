import {
  BaseQueryFn,
  FetchArgs,
  fetchBaseQuery,
  FetchBaseQueryError,
} from "@reduxjs/toolkit/query/react";
import { server } from "../config";
import { userNotExist } from "../reducer/userReducer";

export const TOKEN_KEY = "token";

export const getToken = () => localStorage.getItem(TOKEN_KEY);

/**
 * One base query for every API slice: attaches the bearer token and signs the user out when the server
 * rejects it (expired/revoked token), instead of each slice re-implementing auth.
 */
export const authedBaseQuery = (path: string): BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> => {
  const raw = fetchBaseQuery({
    baseUrl: `${server}/api/v1/${path}`,
    prepareHeaders: (headers) => {
      const token = getToken();
      if (token) headers.set("Authorization", `Bearer ${token}`);
      return headers;
    },
  });

  return async (args, api, extraOptions) => {
    const result = await raw(args, api, extraOptions);
    if (result.error?.status === 401 && getToken()) {
      api.dispatch(userNotExist());
    }
    return result;
  };
};

/** Extracts the server's `{message}` from an RTK Query error. */
export const errorMessage = (error: unknown, fallback = "Something went wrong"): string => {
  const data = (error as FetchBaseQueryError | undefined)?.data as { message?: string } | undefined;
  return data?.message ?? fallback;
};
