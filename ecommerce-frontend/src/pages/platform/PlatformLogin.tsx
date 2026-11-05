import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { errorMessage } from "../../redux/api/baseQuery";
import { PLATFORM_EMAIL_KEY, PLATFORM_TOKEN_KEY, usePlatformLoginMutation } from "../../redux/api/platformAPI";

/** Platform credentials only: no store account can sign in here. */
const PlatformLogin = ({ onDone }: { onDone: () => void }) => {
  const [login, { isLoading }] = usePlatformLoginMutation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = await login({ email, password });
    if ("error" in res) return toast.error(errorMessage(res.error, "Sign in failed"));
    localStorage.setItem(PLATFORM_TOKEN_KEY, res.data.token);
    localStorage.setItem(PLATFORM_EMAIL_KEY, email);
    onDone();
  };

  return (
    <div className="platform-login">
      <h1>Platform sign in</h1>
      <form className="card form-grid" onSubmit={submit}>
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
        </label>
        <label>
          Password
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        </label>
        <button className="btn btn--primary span-2" disabled={isLoading}>
          Sign in
        </button>
      </form>
    </div>
  );
};

export default PlatformLogin;
