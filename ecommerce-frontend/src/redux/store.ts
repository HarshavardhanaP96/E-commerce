import { configureStore } from "@reduxjs/toolkit";
import { userAPI } from "./api/userAPI";
import { productAPI } from "./api/productAPI";
import { orderAPI } from "./api/orderAPI";
import { couponAPI } from "./api/couponAPI";
import { categoryAPI } from "./api/categoryAPI";
import { dashboardAPI } from "./api/dashboardAPI";
import { consentAPI } from "./api/consentAPI";
import { auditAPI } from "./api/auditAPI";
import { userReducer } from "./reducer/userReducer";
import { cartReducer, persistCart } from "./reducer/cartReducer";

export const store = configureStore({
  reducer: {
    [userAPI.reducerPath]: userAPI.reducer,
    [productAPI.reducerPath]: productAPI.reducer,
    [orderAPI.reducerPath]: orderAPI.reducer,
    [couponAPI.reducerPath]: couponAPI.reducer,
    [categoryAPI.reducerPath]: categoryAPI.reducer,
    [dashboardAPI.reducerPath]: dashboardAPI.reducer,
    [consentAPI.reducerPath]: consentAPI.reducer,
    [auditAPI.reducerPath]: auditAPI.reducer,
    [userReducer.name]: userReducer.reducer,
    [cartReducer.name]: cartReducer.reducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(
      userAPI.middleware,
      productAPI.middleware,
      orderAPI.middleware,
      couponAPI.middleware,
      categoryAPI.middleware,
      dashboardAPI.middleware,
      consentAPI.middleware,
      auditAPI.middleware
    ),
});

store.subscribe(() => persistCart(store.getState().cartReducer));

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
