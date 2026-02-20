import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { User } from "../../types/types";


const initialState: { user: User | null; loading: boolean; token: string | null } = {
  user: null,
  loading: true,
  token: localStorage.getItem("token"),
};

export const userReducer = createSlice({
  name: "userReducer",
  initialState,
  reducers: {
    userExist: (state, action: PayloadAction<User>) => {
      state.loading = false;
      state.user = action.payload;
    },
    userNotExist: (state) => {
      state.loading = false;
      state.user = null;
      state.token = null;
      localStorage.removeItem("token");
    },
    setToken: (state, action: PayloadAction<string>) => {
      state.token = action.payload;
    },
  },
});

export const { userExist, userNotExist, setToken } = userReducer.actions;
