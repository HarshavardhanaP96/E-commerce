import { useEffect, useState } from "react";
import { LuAlertCircle, LuCheck, LuShoppingBag } from "react-icons/lu";
import { useDispatch, useSelector } from "react-redux";
import { Link } from "react-router-dom";
import CartItems from "../components/cartItems";
import { errorMessage } from "../redux/api/baseQuery";
import { useLazyDiscountQuery } from "../redux/api/couponAPI";
import { addToCart, applyCoupon, removeCartItem } from "../redux/reducer/cartReducer";
import { RootState } from "../redux/store";
import { CartItem } from "../types/types";
import { formatINR } from "../utils/features";
import { policyPath } from "../legal/policies";

const Cart = () => {
  const { cartItems, subtotal, tax, total, shippingCharges, discount, coupon } = useSelector(
    (state: RootState) => state.cartReducer
  );
  const dispatch = useDispatch();
  const [couponCode, setCouponCode] = useState(coupon);
  const [checkDiscount, { isFetching }] = useLazyDiscountQuery();
  const [couponMessage, setCouponMessage] = useState("");

  const incrementHandler = (item: CartItem) => {
    if (item.quantity >= item.stock) return;
    dispatch(addToCart({ ...item, quantity: item.quantity + 1 }));
  };
  const decrementHandler = (item: CartItem) => {
    if (item.quantity <= 1) return;
    dispatch(addToCart({ ...item, quantity: item.quantity - 1 }));
  };
  const removeHandler = (productId: string) => dispatch(removeCartItem(productId));

  // Validate the coupon with the server after the user stops typing, and again when the subtotal changes
  // (percent coupons and minimum order values depend on it).
  useEffect(() => {
    const code = couponCode.trim();
    if (!code) {
      setCouponMessage("");
      dispatch(applyCoupon({ coupon: "", discount: 0 }));
      return;
    }
    const t = setTimeout(async () => {
      const res = await checkDiscount({ coupon: code, subtotal });
      if (res.data) {
        setCouponMessage("");
        dispatch(applyCoupon({ coupon: code, discount: res.data.discount }));
      } else {
        setCouponMessage(errorMessage(res.error, "Invalid coupon"));
        dispatch(applyCoupon({ coupon: "", discount: 0 }));
      }
    }, 600);
    return () => clearTimeout(t);
  }, [couponCode, subtotal, checkDiscount, dispatch]);

  const itemCount = cartItems.reduce((n, i) => n + i.quantity, 0);
  const valid = !!coupon && coupon === couponCode.trim() && discount > 0;

  return (
    <div className="cart">
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
              key={i.productId}
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
        <p><span>Subtotal</span> <span>{formatINR(subtotal)}</span></p>
        <p><span>Shipping</span> <span>{formatINR(shippingCharges)}</span></p>
        <p><span>Tax (18%)</span> <span>{formatINR(tax)}</span></p>
        <p>
          <span>Discount</span> <em className="green">−{formatINR(Math.min(discount, subtotal))}</em>
        </p>
        <p className="cart__total">
          <span>Total</span> <span>{formatINR(total)}</span>
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
              <LuCheck /> {formatINR(discount)} off with <code>{coupon}</code>
            </span>
          ) : (
            couponMessage && (
              <span className="red">
                <LuAlertCircle /> {couponMessage}
              </span>
            )
          ))}
        <small>Final amount is confirmed at checkout.</small>
        {cartItems.length > 0 && <Link to="/shipping">Checkout</Link>}
        <small className="cart__policies">
          <Link to={policyPath("refunds")}>Returns &amp; refunds</Link> ·{" "}
          <Link to={policyPath("shipping")}>Shipping</Link>
        </small>
      </aside>
    </div>
  );
};

export default Cart;
