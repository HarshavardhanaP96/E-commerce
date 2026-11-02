// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { configureStore } from "@reduxjs/toolkit";
import { Provider } from "react-redux";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { cartReducer } from "../redux/reducer/cartReducer";
import { userReducer } from "../redux/reducer/userReducer";
import { cleanup, render, settle } from "../test/dom";
import { track } from "../utils/tracking";
import Shipping from "./shipping";

// Resolve on a later task, like a real response.
const later = <T,>(value: T) => ({ unwrap: () => new Promise<T>((r) => setTimeout(() => r(value), 0)) });
const checkout = vi.fn(() => later({ checkout: { provider: "fake", providerOrderId: "po_1", total: 500, currency: "INR" } }));
const confirmOrder = vi.fn(() => later({ orderId: "ord_42", message: "Order placed" }));

vi.mock("../redux/api/orderAPI", () => ({
  useCheckoutMutation: () => [checkout],
  useConfirmOrderMutation: () => [confirmOrder],
}));
vi.mock("../redux/api/userAPI", () => ({ useGetMyAddressesQuery: () => ({ data: undefined }) }));
vi.mock("../utils/tracking", async (actual) => ({
  ...(await actual<typeof import("../utils/tracking")>()),
  track: vi.fn(),
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.restoreAllMocks();
  localStorage.clear();
});

let path = "";
const Where = () => {
  path = useLocation().pathname;
  return null;
};

describe("Shipping → pay with the fake gateway", () => {
  it("lands on the new order's page, not the emptied cart, and tracks the purchase once", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const store = configureStore({ reducer: { cartReducer: cartReducer.reducer, userReducer: userReducer.reducer } });
    store.dispatch(cartReducer.actions.addToCart({
      productId: "p1", name: "Tee", price: 500, quantity: 1, stock: 5, photo: "",
    } as Parameters<typeof cartReducer.actions.addToCart>[0]));
    store.dispatch(cartReducer.actions.saveShippingInfo({
      name: "Asha", phone: "9876543210", address: "1 Main St", city: "Pune", state: "MH", country: "India", pinCode: "411001",
    }));

    await render(
      <Provider store={store}>
        <MemoryRouter initialEntries={["/shipping"]}>
          <Where />
          <Routes>
            <Route path="/shipping" element={<Shipping />} />
            <Route path="/cart" element={<p>cart page</p>} />
            <Route path="/order/:id" element={<p>order page</p>} />
          </Routes>
        </MemoryRouter>
      </Provider>,
    );
    document.querySelector("form")!.requestSubmit();
    // jsdom keeps reporting the finished submit as window.event; a browser doesn't, so React gives the
    // redux update and the navigation different priorities there. Run outside act(), which batches both.
    vi.spyOn(window, "event", "get").mockReturnValue(undefined);
    await new Promise((r) => setTimeout(r, 20));
    await settle();

    expect(confirmOrder).toHaveBeenCalledTimes(1);
    expect(path).toBe("/order/ord_42");
    expect(document.body.textContent).toContain("order page");
    expect(store.getState().cartReducer.cartItems).toEqual([]);
    expect(track).toHaveBeenCalledTimes(1);
    expect(track).toHaveBeenCalledWith("purchase", expect.objectContaining({ orderId: "ord_42", value: 500 }));
  });
});
