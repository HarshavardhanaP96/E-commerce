import { brand } from "../tenant/tenant";
declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open: () => void; on: (e: string, cb: () => void) => void };
  }
}

export type RazorpaySuccess = {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
};

const SCRIPT_SRC = "https://checkout.razorpay.com/v1/checkout.js";

const loadScript = () =>
  new Promise<void>((resolve, reject) => {
    if (window.Razorpay) return resolve();
    const script = document.createElement("script");
    script.src = SCRIPT_SRC;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("Could not load Razorpay checkout"));
    document.body.appendChild(script);
  });

/** Opens Razorpay Checkout and resolves with the signed payment, or rejects if the user closes it. */
export const payWithRazorpay = async (options: {
  key: string;
  orderId: string;
  amount: number;
  currency: string;
  name: string;
  email: string;
}): Promise<RazorpaySuccess> => {
  await loadScript();
  return new Promise((resolve, reject) => {
    const rzp = new window.Razorpay!({
      key: options.key,
      order_id: options.orderId,
      amount: options.amount,
      currency: options.currency,
      name: brand.name,
      prefill: { name: options.name, email: options.email },
      theme: { color: "#6366f1" },
      handler: (response: RazorpaySuccess) => resolve(response),
      modal: { ondismiss: () => reject(new Error("Payment cancelled")) },
    });
    rzp.on("payment.failed", () => reject(new Error("Payment failed")));
    rzp.open();
  });
};
