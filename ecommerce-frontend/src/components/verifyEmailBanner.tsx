import { useState } from "react";
import toast from "react-hot-toast";
import { errorMessage } from "../redux/api/baseQuery";
import { useResendVerificationMutation } from "../redux/api/userAPI";

const DISMISSED_KEY = "verify-email-dismissed";

const wasDismissed = () => {
  try {
    return sessionStorage.getItem(DISMISSED_KEY) === "1";
  } catch {
    return false;
  }
};

/** A reminder under the header while the signed-in customer's email is still unverified. Nothing is blocked. */
const VerifyEmailBanner = () => {
  const [hidden, setHidden] = useState(wasDismissed);
  const [resend, { isLoading }] = useResendVerificationMutation();

  if (hidden) return null;

  const dismiss = () => {
    try {
      sessionStorage.setItem(DISMISSED_KEY, "1");
    } catch {
      // private mode: the banner simply comes back on the next page load
    }
    setHidden(true);
  };

  const send = async () => {
    const res = await resend();
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
  };

  return (
    <div className="verify-banner" role="region" aria-label="Email verification">
      <span>Please verify your email address. We sent you a link when you signed up.</span>
      <button className="btn" onClick={send} disabled={isLoading}>
        {isLoading ? "Sending…" : "Resend link"}
      </button>
      <button className="icon-btn" onClick={dismiss} aria-label="Dismiss">×</button>
    </div>
  );
};

export default VerifyEmailBanner;
