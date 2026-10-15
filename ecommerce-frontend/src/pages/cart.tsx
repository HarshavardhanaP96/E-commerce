import { useEffect, useState } from "react";
import { VscError } from "react-icons/vsc";
import { useDispatch, useSelector } from "react-redux";
import { Link } from "react-router-dom";
import CartItems from "../components/cartItems";
import { useLazyDiscountQuery } from "../redux/api/couponAPI";
import { addToCart, applyCoupon, removeCartItem } from "../redux/reducer/cartReducer";
import { RootState } from "../redux/store";
import { CartItem } from "../types/types";
import { formatINR } from "../utils/features";

const Cart = () => {
  const { cartItems, subtotal, tax, total, shippingCharges, discount, coupon } = useSelector(
    (state: RootState) => state.cartReducer
  );
  const dispatch = useDispatch();
  const [couponCode, setCouponCode] = useState(coupon);
  const [checkDiscount, { isFetching, isError }] = useLazyDiscountQuery();

  const incrementHandler = (item: CartItem) => {
    if (item.quantity >= item.stock) return;
    dispatch(addToCart({ ...item, quantity: item.quantity + 1 }));
  };
  const decrementHandler = (item: CartItem) => {
    if (item.quantity <= 1) return;
    dispatch(addToCart({ ...item, quantity: item.quantity - 1 }));
  };
  const removeHandler = (productId: string) => dispatch(removeCartItem(productId));

  // Validate the coupon with the server after the user stops typing.
  useEffect(() => {
    const code = couponCode.trim();
    if (!code) {
      dispatch(applyCoupon({ coupon: "", discount: 0 }));
      return;
    }
    const t = setTimeout(async () => {
      const res = await checkDiscount(code);
      dispatch(applyCoupon(res.data ? { coupon: code, discount: res.data.discount } : { coupon: "", discount: 0 }));
    }, 600);
    return () => clearTimeout(t);
  }, [couponCode, checkDiscount, dispatch]);

  const valid = !!coupon && coupon === couponCode.trim() && discount > 0;

  return (
    <div className="cart">
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
          <h1>No items added</h1>
        )}
      </main>
      <aside>
        <p>Subtotal: {formatINR(subtotal)}</p>
        <p>Shipping charges: {formatINR(shippingCharges)}</p>
        <p>Tax (18%): {formatINR(tax)}</p>
        <p>
          Discount: <em className="red">−{formatINR(Math.min(discount, subtotal))}</em>
        </p>
        <p>
          <b>Total: {formatINR(total)}</b>
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
              {formatINR(discount)} off using <code>{coupon}</code>
            </span>
          ) : (
            isError && (
              <span className="red">
                Invalid coupon <VscError />
              </span>
            )
          ))}
        <small>Final amount is confirmed at checkout.</small>
        {cartItems.length > 0 && <Link to="/shipping">Check out</Link>}
      </aside>
    </div>
  );
};

export default Cart;
