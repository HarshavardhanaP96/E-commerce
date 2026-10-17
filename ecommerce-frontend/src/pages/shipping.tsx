import { ChangeEvent, FormEvent, useEffect, useState } from "react";
import toast from "react-hot-toast";
import { LuArrowLeft, LuLock } from "react-icons/lu";
import { useDispatch, useSelector } from "react-redux";
import { Link, useNavigate } from "react-router-dom";
import { policyPath } from "../legal/policies";
import { errorMessage } from "../redux/api/baseQuery";
import { useCheckoutMutation, useConfirmOrderMutation } from "../redux/api/orderAPI";
import { resetCart, saveShippingInfo } from "../redux/reducer/cartReducer";
import { RootState } from "../redux/store";
import { ShippingInfo } from "../types/types";
import { formatINR } from "../utils/features";
import { payWithRazorpay } from "../utils/razorpay";

const Shipping = () => {
  const navigate = useNavigate();
  const dispatch = useDispatch();
  const { cartItems, coupon, shippingInfo: saved } = useSelector((s: RootState) => s.cartReducer);
  const user = useSelector((s: RootState) => s.userReducer.user);
  const [shippingInfo, setShippingInfo] = useState<ShippingInfo>(saved);
  const [checkout] = useCheckoutMutation();
  const [confirmOrder] = useConfirmOrderMutation();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (cartItems.length === 0) navigate("/cart");
  }, [cartItems, navigate]);

  const changeHandler = (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setShippingInfo((prev) => ({ ...prev, [e.target.name]: e.target.value }));

  const submitHandler = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    dispatch(saveShippingInfo(shippingInfo));
    try {
      // 1. Server prices the cart and opens a payment order.
      const quote = await checkout({
        items: cartItems.map((i) => ({ productId: i.productId, quantity: i.quantity })),
        coupon: coupon || undefined,
      }).unwrap();
      const c = quote.checkout;

      // 2. Collect payment (fake gateway in local dev, Razorpay otherwise).
      let paymentId = "fake_pay";
      let signature = "fake";
      if (c.provider === "razorpay") {
        const paid = await payWithRazorpay({
          key: c.key,
          orderId: c.providerOrderId,
          amount: c.amount,
          currency: c.currency,
          name: user?.name ?? "",
          email: user?.email ?? "",
        });
        paymentId = paid.razorpay_payment_id;
        signature = paid.razorpay_signature;
      } else if (!confirm(`Test mode: pay ${formatINR(c.total)} with the fake gateway?`)) {
        return;
      }

      // 3. Server verifies the payment, reserves stock and creates the order.
      const res = await confirmOrder({ providerOrderId: c.providerOrderId, paymentId, signature, shippingInfo }).unwrap();
      dispatch(resetCart());
      toast.success(res.message);
      navigate(`/order/${res.orderId}`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : errorMessage(err, "Checkout failed"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="shipping">
      <button className="back-btn" onClick={() => navigate("/cart")}>
        <LuArrowLeft /> Back to cart
      </button>

      <form onSubmit={submitHandler}>
        <h1>Shipping address</h1>
        <p>Where should we deliver your order?</p>

        <label className="span-2">
          Address
          <input type="text" name="address" placeholder="House no., street, area" value={shippingInfo.address}
                 onChange={changeHandler} maxLength={200} required autoComplete="street-address" />
        </label>
        <label>
          City
          <input type="text" name="city" value={shippingInfo.city}
                 onChange={changeHandler} maxLength={80} required autoComplete="address-level2" />
        </label>
        <label>
          State
          <input type="text" name="state" value={shippingInfo.state}
                 onChange={changeHandler} maxLength={80} required autoComplete="address-level1" />
        </label>
        <label>
          Country
          <select name="country" required value={shippingInfo.country} onChange={changeHandler}>
            <option value="">Choose a country</option>
            <option value="India">India</option>
          </select>
        </label>
        <label>
          Pin code
          <input type="text" inputMode="numeric" pattern="\d{4,10}" title="4-10 digits" name="pinCode"
                 value={shippingInfo.pinCode} onChange={changeHandler} required autoComplete="postal-code" />
        </label>
        <button type="submit" className="span-2" disabled={busy}>
          <LuLock /> {busy ? "Processing…" : "Pay & place order"}
        </button>
        <p className="shipping__terms span-2">
          By placing this order you agree to our <Link to={policyPath("terms")} target="_blank">Terms</Link>,{" "}
          <Link to={policyPath("refunds")} target="_blank">Cancellation &amp; Refund Policy</Link> and{" "}
          <Link to={policyPath("shipping")} target="_blank">Shipping Policy</Link>. Payment is processed securely
          by Razorpay.
        </p>
      </form>
    </div>
  );
};

export default Shipping;
