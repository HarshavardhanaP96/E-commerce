import { useEffect, useRef, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { Link, useSearchParams } from "react-router-dom";
import { errorMessage } from "../redux/api/baseQuery";
import { useLazyGetMyProfileQuery, useVerifyEmailMutation } from "../redux/api/userAPI";
import { userExist } from "../redux/reducer/userReducer";
import { RootState } from "../redux/store";

type Outcome = { state: "working" } | { state: "done" } | { state: "failed"; message: string };

/** The landing page of the link in the verification email. Works signed in or out. */
const VerifyEmail = () => {
  const [params] = useSearchParams();
  const token = params.get("token");
  const dispatch = useDispatch();
  const signedIn = useSelector((s: RootState) => !!s.userReducer.user);
  const [verifyEmail] = useVerifyEmailMutation();
  const [fetchProfile] = useLazyGetMyProfileQuery();
  const [outcome, setOutcome] = useState<Outcome>(token ? { state: "working" } : { state: "failed", message: "This link is missing its code." });
  // A link can be used once, so React's double effect run in dev must not spend it twice.
  const started = useRef(false);

  useEffect(() => {
    if (!token || started.current) return;
    started.current = true;
    verifyEmail({ token }).then(async (res) => {
      if ("error" in res) return setOutcome({ state: "failed", message: errorMessage(res.error, "This link is invalid or has expired.") });
      setOutcome({ state: "done" });
      if (signedIn) fetchProfile().unwrap().then((data) => dispatch(userExist(data.user))).catch(() => undefined);
    });
  }, [token, verifyEmail, signedIn, fetchProfile, dispatch]);

  return (
    <div className="login">
      <main>
        <h1 className="heading">Verify email</h1>
        {outcome.state === "working" && <p role="status">Verifying your email…</p>}
        {outcome.state === "done" && (
          <>
            <p role="status">Your email address is verified. Thank you!</p>
            <Link to="/">Continue shopping</Link>
          </>
        )}
        {outcome.state === "failed" && (
          <>
            <p role="alert">{outcome.message}</p>
            <p>
              {signedIn ? "Use “Resend” in the banner at the top to get a new link." : "Sign in, then ask for a new link from the banner at the top."}
            </p>
            <Link to={signedIn ? "/" : "/login"}>{signedIn ? "Back to the shop" : "Sign in"}</Link>
          </>
        )}
      </main>
    </div>
  );
};

export default VerifyEmail;
