import { LuCheck } from "react-icons/lu";
import { CheckoutStep, checkoutSteps } from "../utils/checkout";

/** Cart → Address → Payment. Payment is Razorpay's own window, opened from the address page. */
const CheckoutSteps = ({ current }: { current: CheckoutStep }) => (
  <ol className="checkout-steps" aria-label="Checkout progress">
    {checkoutSteps(current).map((s, i) => (
      <li
        key={s.key}
        className={`checkout-steps__step is-${s.state}`}
        aria-current={s.state === "current" ? "step" : undefined}
      >
        <span aria-hidden>{s.state === "done" ? <LuCheck /> : i + 1}</span>
        {s.label}
        {s.state === "done" && <span className="sr-only"> (done)</span>}
      </li>
    ))}
  </ol>
);

export default CheckoutSteps;
