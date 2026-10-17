import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { LuDownload, LuTrash2 } from "react-icons/lu";
import { useDispatch, useSelector } from "react-redux";
import { Link } from "react-router-dom";
import { errorMessage } from "../../redux/api/baseQuery";
import { useDecideConsentMutation, useMyConsentQuery } from "../../redux/api/consentAPI";
import { useLazyMyOrdersQuery } from "../../redux/api/orderAPI";
import { useDeleteMyAccountMutation } from "../../redux/api/userAPI";
import { resetCart } from "../../redux/reducer/cartReducer";
import { userNotExist } from "../../redux/reducer/userReducer";
import { RootState } from "../../redux/store";
import { BUSINESS, policyPath } from "../../legal/policies";

const formatDateTime = (iso: string) =>
  new Date(iso).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

/** Self-service privacy rights: consent choices, access (export) and erasure (compliance-consent.md C13). */
const PrivacySettings = () => {
  const dispatch = useDispatch();
  const user = useSelector((s: RootState) => s.userReducer.user);
  const { data: consent } = useMyConsentQuery(user?._id ?? "", { skip: !user });
  const [decide, { isLoading: saving }] = useDecideConsentMutation();
  const [fetchOrders] = useLazyMyOrdersQuery();
  const [deleteAccount, { isLoading: deleting }] = useDeleteMyAccountMutation();
  const [password, setPassword] = useState("");
  const [confirmed, setConfirmed] = useState(false);

  const marketing = consent?.consents.marketing_email?.granted ?? false;

  const toggleMarketing = async () => {
    try {
      await decide({ purpose: "marketing_email", granted: !marketing, source: "settings" }).unwrap();
      toast.success(marketing ? "You won't receive marketing emails" : "You'll receive offers and updates");
    } catch (err) {
      toast.error(errorMessage(err, "Could not save your choice"));
    }
  };

  const exportData = async () => {
    try {
      const orders = await fetchOrders().unwrap();
      const data = {
        exportedAt: new Date().toISOString(),
        profile: user,
        orders: orders.orders,
        consentHistory: consent?.history ?? [],
      };
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
      const a = document.createElement("a");
      a.href = url;
      a.download = `6pack-my-data-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      toast.error(errorMessage(err, "Could not prepare your data"));
    }
  };

  const deleteHandler = async (e: FormEvent) => {
    e.preventDefault();
    try {
      const res = await deleteAccount({ password }).unwrap();
      // Signing out makes the protected route send the user to /login.
      dispatch(resetCart());
      dispatch(userNotExist());
      toast.success(res.message);
    } catch (err) {
      toast.error(errorMessage(err, "Could not delete your account"));
    }
  };

  return (
    <div className="privacy-settings">
      <h1>Privacy &amp; data</h1>

      <section>
        <h2>Communication preferences</h2>
        <label className="checkbox">
          <input type="checkbox" checked={marketing} disabled={!consent || saving} onChange={toggleMarketing} />
          <span>Email me offers and updates</span>
        </label>
        <p>
          Order and account emails (receipts, delivery updates, password resets) are always sent. They aren't
          marketing.
        </p>
      </section>

      <section>
        <h2>What you've agreed to</h2>
        {consent && consent.history.length > 0 ? (
          <ul className="privacy-settings__history">
            {consent.history.map((h, i) => (
              <li key={i}>
                <span>
                  {h.granted ? "Accepted" : "Declined"}{" "}
                  {h.purpose === "marketing_email" ? "marketing emails" : h.purpose === "terms" ? "Terms" : "Privacy Policy"}
                  {h.purpose !== "marketing_email" && ` (version ${h.version})`}
                </span>
                <time dateTime={h.at}>{formatDateTime(h.at)}</time>
              </li>
            ))}
          </ul>
        ) : (
          <p>No records yet.</p>
        )}
        <p>
          Read the <Link to={policyPath("terms")}>Terms</Link> and the{" "}
          <Link to={policyPath("privacy")}>Privacy Policy</Link>.
        </p>
      </section>

      <section>
        <h2>Download your data</h2>
        <p>A JSON file with your profile, your orders and your consent history.</p>
        <button type="button" className="privacy-settings__secondary" onClick={exportData}>
          <LuDownload /> Download my data
        </button>
      </section>

      <section className="privacy-settings__danger">
        <h2>Delete your account</h2>
        <p>
          This permanently deletes your profile and photo, and signs you out. Order records are kept for tax
          purposes, but your street address and PIN code are removed from them. You can do this once all your
          orders have been delivered. Need a correction instead? Write to {BUSINESS.grievanceEmail}.
        </p>
        {user?.role === "admin" ? (
          <p><strong>Admin accounts can't be deleted here. Ask another admin to remove it.</strong></p>
        ) : (
          <form onSubmit={deleteHandler}>
            <label>
              Confirm your password
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required
                     maxLength={72} autoComplete="current-password" />
            </label>
            <label className="checkbox">
              <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} required />
              <span>I understand this can't be undone.</span>
            </label>
            <button type="submit" disabled={!confirmed || !password || deleting}>
              <LuTrash2 /> {deleting ? "Deleting…" : "Delete my account"}
            </button>
          </form>
        )}
      </section>
    </div>
  );
};

export default PrivacySettings;
