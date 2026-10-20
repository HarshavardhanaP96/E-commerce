import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { CartItem, ShippingInfo } from "../../types/types";
import { userNotExist } from "./userReducer";
import { cartKey } from "../../utils/variants";
import { cartTotals, commerce } from "../../utils/pricing";

const CART_KEY = "cart";

type CartState = {
  cartItems: CartItem[];
  subtotal: number;
  tax: number;
  shippingCharges: number;
  discount: number;
  coupon: string;
  total: number;
  shippingInfo: ShippingInfo;
};

const emptyCart: CartState = {
  cartItems: [],
  subtotal: 0,
  tax: 0,
  shippingCharges: 0,
  discount: 0,
  coupon: "",
  total: 0,
  shippingInfo: { address: "", city: "", state: "", country: "India", pinCode: "" },
};

const recalculate = (state: CartState) => {
  Object.assign(state, cartTotals(state.cartItems, state.discount, commerce));
};

const loadCart = (): CartState => {
  try {
    const saved = localStorage.getItem(CART_KEY);
    if (!saved) return emptyCart;
    const state = { ...emptyCart, ...JSON.parse(saved) } as CartState;
    recalculate(state);
    return state;
  } catch {
    return emptyCart;
  }
};

export const persistCart = (state: CartState) => {
  try {
    const { cartItems, coupon, discount, shippingInfo } = state;
    localStorage.setItem(CART_KEY, JSON.stringify({ cartItems, coupon, discount, shippingInfo }));
  } catch {
    // storage unavailable (private mode / quota): cart simply won't survive a reload
  }
};

export const cartReducer = createSlice({
  name: "cartReducer",
  initialState: loadCart,
  reducers: {
    addToCart: (state, action: PayloadAction<CartItem>) => {
      const index = state.cartItems.findIndex((i) => cartKey(i) === cartKey(action.payload));
      if (index !== -1) state.cartItems[index] = action.payload;
      else state.cartItems.push(action.payload);
      recalculate(state);
    },
    removeCartItem: (state, action: PayloadAction<{ productId: string; variantId?: string }>) => {
      state.cartItems = state.cartItems.filter((i) => cartKey(i) !== cartKey(action.payload));
      recalculate(state);
    },
    applyCoupon: (state, action: PayloadAction<{ coupon: string; discount: number }>) => {
      state.coupon = action.payload.coupon;
      state.discount = action.payload.discount;
      recalculate(state);
    },
    saveShippingInfo: (state, action: PayloadAction<ShippingInfo>) => {
      state.shippingInfo = action.payload;
    },
    resetCart: (state) => ({ ...emptyCart, shippingInfo: state.shippingInfo }),
  },
  // A home address shouldn't outlive the session on a shared device (compliance-consent.md §4).
  extraReducers: (builder) => {
    builder.addCase(userNotExist, (state) => {
      state.shippingInfo = emptyCart.shippingInfo;
    });
  },
});

export const { addToCart, removeCartItem, applyCoupon, saveShippingInfo, resetCart } = cartReducer.actions;
