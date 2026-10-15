import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { User } from "../../types/types";

const TOKEN_KEY = "token";

const initialState: { user: User | null; loading: boolean } = {
  user: null,
  loading: true,
};

export const userReducer = createSlice({
  name: "userReducer",
  initialState,
  reducers: {
    userExist: (state, action: PayloadAction<User>) => {
      state.loading = false;
      state.user = action.payload;
    },
    userLoggedIn: (state, action: PayloadAction<{ user: User; token: string }>) => {
      localStorage.setItem(TOKEN_KEY, action.payload.token);
      state.loading = false;
      state.user = action.payload.user;
    },
    userNotExist: (state) => {
      localStorage.removeItem(TOKEN_KEY);
      state.loading = false;
      state.user = null;
    },
  },
});

export const { userExist, userLoggedIn, userNotExist } = userReducer.actions;
