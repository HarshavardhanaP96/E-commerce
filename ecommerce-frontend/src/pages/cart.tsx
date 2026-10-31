import { useEffect, useState } from "react";
import { LuAlertCircle, LuCheck, LuShoppingBag } from "react-icons/lu";
import { useDispatch, useSelector } from "react-redux";
import { Link } from "react-router-dom";
import CartItems from "../components/cartItems";
import { errorMessage } from "../redux/api/baseQuery";
import { useQuoteMutation } from "../redux/api/orderAPI";
import { addToCart, applyCoupon, removeCartItem } from "../redux/reducer/cartReducer";
import { RootState } from "../redux/store";
import { CartItem } from "../types/types";
import { formatMoney } from "../utils/features";
import { cartKey } from "../utils/variants";
import { policyPath } from "../legal/policies";
import CheckoutSteps from "../components/checkoutSteps";
import { cartLine, track } from "../utils/tracking";

const Cart = () => {
  const { cartItems, subtotal, tax, total, shippingCharges, discount, coupon } = useSelector(
    (state: RootState) => state.cartReducer
  );
  const dispatch = useDispatch();
  const [couponCode, setCouponCode] = useState(coupon);
  const [quote, { isLoading: isFetching }] = useQuoteMutation();
  const [couponMessage, setCouponMessage] = useState("");

  const incrementHandler = (item: CartItem) => {
    if (item.quantity >= item.stock) return;
    dispatch(addToCart({ ...item, quantity: item.quantity + 1 }));
  };
  const decrementHandler = (item: CartItem) => {
    if (item.quantity <= 1) return;
    dispatch(addToCart({ ...item, quantity: item.quantity - 1 }));
  };
  const removeHandler = (item: CartItem) =>
    dispatch(removeCartItem({ productId: item.productId, variantId: item.variantId }));

  // Price the cart with the server after the user stops typing, and again when the cart changes: a coupon can be
  // limited to some products, so its discount depends on what is in the cart, not just the subtotal.
  const lines = JSON.stringify(cartItems.map((i) => ({ productId: i.productId, quantity: i.quantity })));
  useEffect(() => {
    const code = couponCode.trim();
    if (!code || cartItems.length === 0) {
      setCouponMessage("");
      dispatch(applyCoupon({ coupon: "", discount: 0 }));
      return;
    }
    const t = setTimeout(async () => {
      const res = await quote({ items: JSON.parse(lines), coupon: code });
      if (res.data) {
        setCouponMessage("");
        dispatch(applyCoupon({ coupon: code, discount: res.data.quote.discount }));
      } else {
        setCouponMessage(errorMessage(res.error, "Invalid coupon"));
        dispatch(applyCoupon({ coupon: "", discount: 0 }));
      }
    }, 600);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `lines` is the serialised cartItems; depending on both would re-run on every quantity render
  }, [couponCode, lines, quote, dispatch]);

  const itemCount = cartItems.reduce((n, i) => n + i.quantity, 0);
  const valid = !!coupon && coupon === couponCode.trim() && discount > 0;

  return (
    <div className="cart">
      {cartItems.length > 0 && <CheckoutSteps current="cart" />}
      <h1>
        Your cart{itemCount > 0 && <small>{itemCount} {itemCount === 1 ? "item" : "items"}</small>}
      </h1>
      <main>
        {cartItems.length > 0 ? (
          cartItems.map((i) => (
            <CartItems
              incrementHandler={incrementHandler}
              decrementHandler={decrementHandler}
              removeHandler={removeHandler}
              key={cartKey(i)}
              cartItem={i}
            />
          ))
        ) : (
          <div className="empty-state">
            <LuShoppingBag />
            <p>Your cart is empty.</p>
            <Link to="/search">Start shopping</Link>
          </div>
        )}
      </main>
      <aside>
        <h2>Order summary</h2>
        <p><span>Subtotal</span> <span>{formatMoney(subtotal)}</span></p>
        <p><span>Shipping</span> <span>{formatMoney(shippingCharges)}</span></p>
        <p><span>Tax (18%)</span> <span>{formatMoney(tax)}</span></p>
        <p>
          <span>Discount</span> <em className="green">−{formatMoney(Math.min(discount, subtotal))}</em>
        </p>
        <p className="cart__total">
          <span>Total</span> <span>{formatMoney(total)}</span>
        </p>
        <input
          type="text"
          placeholder="Coupon code"
          value={couponCode}
          onChange={(e) => setCouponCode(e.target.value.toUpperCase())}
          maxLength={32}
        />
        {couponCode.trim() && !isFetching &&
          (valid ? (
            <span className="green">
              <LuCheck /> {formatMoney(discount)} off with <code>{coupon}</code>
            </span>
          ) : (
            couponMessage && (
              <span className="red">
                <LuAlertCircle /> {couponMessage}
              </span>
            )
          ))}
        <small>Final amount is confirmed at checkout.</small>
        {cartItems.length > 0 && (
          <Link to="/shipping" onClick={() => track("begin_checkout", { items: cartItems.map(cartLine), value: total })}>
            Checkout
          </Link>
        )}
        <small className="cart__policies">
          <Link to={policyPath("refunds")}>Returns &amp; refunds</Link> ·{" "}
          <Link to={policyPath("shipping")}>Shipping</Link>
        </small>
      </aside>
    </div>
  );
};

export default Cart;
