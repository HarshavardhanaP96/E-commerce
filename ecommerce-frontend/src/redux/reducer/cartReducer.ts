import { createSlice, PayloadAction } from "@reduxjs/toolkit";
import { CartItem, ShippingInfo } from "../../types/types";
import { userNotExist } from "./userReducer";
import { cartKey } from "../../utils/variants";

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

/** Mirrors the server's PriceCalculator for display only; the server recomputes at checkout. */
const recalculate = (state: CartState) => {
  const subtotal = state.cartItems.reduce((total, item) => total + item.price * item.quantity, 0);
  state.subtotal = subtotal;
  state.shippingCharges = subtotal === 0 || subtotal > 1000 ? 0 : 200;
  const DEFAULT_TAX_RATE = 18;
  const taxableByRate = new Map<number, number>();
  for (const item of state.cartItems) {
    const rate = item.taxRate ?? DEFAULT_TAX_RATE;
    taxableByRate.set(rate, (taxableByRate.get(rate) ?? 0) + item.price * item.quantity);
  }
  state.tax = [...taxableByRate].reduce((sum, [rate, amount]) => sum + Math.round((amount * rate) / 100), 0);
  const discount = Math.min(state.discount, subtotal);
  state.total = subtotal + state.tax + state.shippingCharges - discount;
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
