import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { errorMessage } from "../redux/api/baseQuery";
import { useForgotPasswordMutation, useResetPasswordMutation } from "../redux/api/userAPI";

/** Step 1 (no token): request a reset link. Step 2 (?token=… from the email): choose a new password. */
const ForgotPassword = () => {
  const [params] = useSearchParams();
  const token = params.get("token");
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [sent, setSent] = useState(false);
  const [forgotPassword, { isLoading: sending }] = useForgotPasswordMutation();
  const [resetPassword, { isLoading: resetting }] = useResetPasswordMutation();

  const requestLink = async (e: FormEvent) => {
    e.preventDefault();
    const res = await forgotPassword({ email });
    if ("error" in res) return toast.error(errorMessage(res.error));
    setSent(true);
  };

  const reset = async (e: FormEvent) => {
    e.preventDefault();
    if (password !== confirmPassword) return toast.error("Passwords do not match");
    const res = await resetPassword({ token: token!, password });
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    navigate("/login");
  };

  return (
    <div className="login">
      <main>
        <h1 className="heading">Reset password</h1>
        {token ? (
          <form onSubmit={reset}>
            <div>
              <label>New password</label>
              <input type="password" minLength={8} maxLength={72} value={password}
                     onChange={(e) => setPassword(e.target.value)} required autoComplete="new-password" />
            </div>
            <div>
              <label>Confirm password</label>
              <input type="password" minLength={8} maxLength={72} value={confirmPassword}
                     onChange={(e) => setConfirmPassword(e.target.value)} required autoComplete="new-password" />
            </div>
            <button type="submit" disabled={resetting}>{resetting ? "Updating…" : "Update password"}</button>
          </form>
        ) : sent ? (
          <p className="login__sub">If an account exists for <b>{email}</b>, we have sent a reset link. It expires in 15 minutes.</p>
        ) : (
          <form onSubmit={requestLink}>
            <div>
              <label>Email address</label>
              <input type="email" placeholder="you@example.com" value={email}
                     onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
            </div>
            <button type="submit" disabled={sending}>{sending ? "Sending…" : "Send reset link"}</button>
          </form>
        )}
        <p><Link to="/login">Back to login</Link></p>
      </main>
    </div>
  );
};

export default ForgotPassword;
