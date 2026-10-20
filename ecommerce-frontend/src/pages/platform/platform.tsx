import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { AUDIT_ACTIONS, summariseChanges } from "../../redux/api/auditAPI";
import { errorMessage } from "../../redux/api/baseQuery";
import {
  downloadTenantExport,
  PLATFORM_TOKEN_KEY,
  PlatformTenant,
  useCreateTenantMutation,
  usePlatformAuditQuery,
  usePlatformLoginMutation,
  useSetTenantStatusMutation,
  useTenantsQuery,
  useUpdateTenantMutation,
} from "../../redux/api/platformAPI";

/** Settings an operator may edit as JSON; the server validates every field. */
const editable = (t: PlatformTenant) => ({
  name: t.name,
  domains: t.domains,
  theme: t.theme,
  locale: t.locale,
  currency: t.currency,
  legal: t.legal,
  payment: t.payment,
  mail: t.mail,
  storefront: t.storefront,
  features: t.features,
  limits: t.limits,
});

const Login = ({ onDone }: { onDone: () => void }) => {
  const [login, { isLoading }] = usePlatformLoginMutation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = await login({ email, password });
    if ("error" in res) return toast.error(errorMessage(res.error, "Sign in failed"));
    localStorage.setItem(PLATFORM_TOKEN_KEY, res.data.token);
    onDone();
  };

  return (
    <div className="container platform">
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

/** Read-only: every store's audit log, newest first. Platform actions appear here and in the store's own log. */
const PlatformAudit = ({ tenants }: { tenants: PlatformTenant[] }) => {
  const [tenantId, setTenantId] = useState("");
  const [action, setAction] = useState("");
  const [page, setPage] = useState(1);
  const { data, isFetching, isError, error } = usePlatformAuditQuery({ tenantId, action, page });
  const storeName = (id: string | null) => tenants.find((t) => t.id === id)?.slug ?? id ?? "—";
  const totalPage = data?.totalPage ?? 0;

  return (
    <section className="card">
      <h2>Audit log (all stores)</h2>
      <div className="platform__filters">
        <select value={tenantId} onChange={(e) => {
            setTenantId(e.target.value);
            setPage(1);
          }} aria-label="Store">
          <option value="">All stores</option>
          {tenants.map((t) => (
            <option key={t.id} value={t.id}>{t.slug}</option>
          ))}
        </select>
        <select value={action} onChange={(e) => {
            setAction(e.target.value);
            setPage(1);
          }} aria-label="Action">
          <option value="">All actions</option>
          {AUDIT_ACTIONS.map((a) => (
            <option key={a} value={a}>{a}</option>
          ))}
        </select>
      </div>
      <table className="platform__table">
        <thead>
          <tr>
            <th>When</th>
            <th>Store</th>
            <th>Who</th>
            <th>Action</th>
            <th>Changes</th>
          </tr>
        </thead>
        <tbody>
          {isFetching && (
            <tr>
              <td colSpan={5}>Loading…</td>
            </tr>
          )}
          {!isFetching && isError && (
            <tr>
              <td colSpan={5}>{errorMessage(error)}</td>
            </tr>
          )}
          {!isFetching && !isError && (data?.entries ?? []).length === 0 && (
            <tr>
              <td colSpan={5}>No entries</td>
            </tr>
          )}
          {!isFetching &&
            (data?.entries ?? []).map((e) => (
              <tr key={e._id}>
                <td title={e.at}>{new Date(e.at).toLocaleString("en-IN", { dateStyle: "short", timeStyle: "short" })}</td>
                <td>{storeName(e.tenantId)}</td>
                <td>{e.actorId ? `${e.actorRole} ${e.actorId.slice(-6)}` : e.actorRole}</td>
                <td>{e.action}</td>
                <td>{summariseChanges(e.changes)}</td>
              </tr>
            ))}
        </tbody>
      </table>
      {totalPage > 1 && (
        <div className="platform__filters">
          <button className="btn" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            Previous
          </button>
          <span>
            Page {page} of {totalPage}
          </span>
          <button className="btn" disabled={page >= totalPage} onClick={() => setPage(page + 1)}>
            Next
          </button>
        </div>
      )}
    </section>
  );
};

const Tenants = ({ onSignOut }: { onSignOut: () => void }) => {
  const { data, isLoading, isError, error } = useTenantsQuery();
  const [createTenant, { isLoading: creating }] = useCreateTenantMutation();
  const [updateTenant, { isLoading: saving }] = useUpdateTenantMutation();
  const [setStatus] = useSetTenantStatusMutation();
  const [form, setForm] = useState({ slug: "", name: "", domain: "", adminName: "", adminEmail: "", adminPassword: "" });
  const [trial, setTrial] = useState(false);
  const [editing, setEditing] = useState<PlatformTenant | null>(null);
  const [json, setJson] = useState("");

  if (isError && (error as { status?: number })?.status === 401) {
    localStorage.removeItem(PLATFORM_TOKEN_KEY);
    onSignOut();
  }

  const set = (field: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const create = async (e: FormEvent) => {
    e.preventDefault();
    const admin = form.adminEmail
      ? { name: form.adminName, email: form.adminEmail, password: form.adminPassword }
      : undefined;
    const res = await createTenant({ slug: form.slug.trim(), name: form.name.trim(), domains: [form.domain.trim()], trial, admin });
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    setForm({ slug: "", name: "", domain: "", adminName: "", adminEmail: "", adminPassword: "" });
    setTrial(false);
  };

  const startEdit = (t: PlatformTenant) => {
    setEditing(t);
    setJson(JSON.stringify(editable(t), null, 2));
  };

  const save = async () => {
    if (!editing) return;
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(json);
    } catch {
      return toast.error("That is not valid JSON");
    }
    const res = await updateTenant({ id: editing.id, body });
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    setEditing(null);
  };

  const change = async (t: PlatformTenant, action: "suspend" | "activate" | "cancel" | "offboard" | "restore") => {
    if (action === "suspend" && !confirm(`Suspend ${t.name}? Its storefront will stop working until reactivated.`)) return;
    if (action === "cancel" && !confirm(`Cancel ${t.name}? Use this when the store owner leaves; the storefront closes.`)) return;
    if (
      action === "offboard" &&
      !confirm(
        `Offboard ${t.name}? The storefront closes now and all its data and photos are deleted after the grace period. ` +
          "Export the store first if its data is needed.",
      )
    )
      return;
    const res = await setStatus({ id: t.id, action });
    if ("error" in res) toast.error(errorMessage(res.error));
    else toast.success(res.data.message);
  };

  const exportStore = async (t: PlatformTenant) => {
    try {
      await downloadTenantExport(t);
    } catch (e) {
      toast.error((e as Error).message);
    }
  };

  return (
    <div className="container platform">
      <h1>
        Tenants{" "}
        <button
          className="btn"
          onClick={() => {
            localStorage.removeItem(PLATFORM_TOKEN_KEY);
            onSignOut();
          }}
        >
          Sign out
        </button>
      </h1>

      <section className="card">
        <table className="platform__table">
          <thead>
            <tr>
              <th>Store</th>
              <th>Domains</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {isLoading && (
              <tr>
                <td colSpan={4}>Loading…</td>
              </tr>
            )}
            {(data?.tenants ?? []).map((t) => (
              <tr key={t.id}>
                <td>
                  <strong>{t.name}</strong> <small>({t.slug})</small>
                </td>
                <td>{t.domains.join(", ")}</td>
                <td>
                  <span className={`badge badge--${t.status === "ACTIVE" || t.status === "TRIAL" ? "active" : "inactive"}`}>
                    {t.status.toLowerCase().replace(/_/g, " ")}
                  </span>
                  {t.trialEndsAt && (
                    <small className="platform__due">
                      trial ends {new Date(t.trialEndsAt).toLocaleDateString("en-IN", { dateStyle: "medium" })}
                    </small>
                  )}
                  {t.deletionDueAt && (
                    <small className="platform__due">
                      data deleted on {new Date(t.deletionDueAt).toLocaleDateString("en-IN", { dateStyle: "medium" })}
                    </small>
                  )}
                </td>
                <td className="platform__actions">
                  {t.status !== "DELETED" && (
                    <>
                      <button className="btn" onClick={() => startEdit(t)}>
                        Edit
                      </button>
                      <button className="btn" onClick={() => exportStore(t)}>
                        Export
                      </button>
                    </>
                  )}
                  {t.slug !== "default" && !["SCHEDULED_FOR_DELETION", "DELETED"].includes(t.status) && (
                    <>
                      {t.status !== "ACTIVE" && (
                        <button className="btn" onClick={() => change(t, "activate")}>
                          Activate
                        </button>
                      )}
                      {(t.status === "ACTIVE" || t.status === "TRIAL") && (
                        <button className="btn" onClick={() => change(t, "suspend")}>
                          Suspend
                        </button>
                      )}
                      {t.status !== "CANCELLED" && (
                        <button className="btn" onClick={() => change(t, "cancel")}>
                          Cancel
                        </button>
                      )}
                      <button className="btn" onClick={() => change(t, "offboard")}>
                        Offboard
                      </button>
                    </>
                  )}
                  {t.status === "SCHEDULED_FOR_DELETION" && (
                    <button className="btn" onClick={() => change(t, "restore")}>
                      Restore
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {editing && (
        <section className="card">
          <h2>Edit {editing.name}</h2>
          <p>
            Theme tokens, legal versions, the payment account reference (the secret lives in the server environment, never
            here), the storefront details, the email link base and feature switches (for example <code>"features": {"{"}"variants": true{"}"}</code>;
            an empty <code>{"{}"}</code> returns every feature to the platform default) and limits (<code>products</code>,{" "}
            <code>uploadBytes</code>, <code>requestsPerMinute</code>; <code>null</code> uses the platform default). Unlisted fields stay
            as they are.
          </p>
          <textarea className="platform__json" rows={18} value={json} onChange={(e) => setJson(e.target.value)} spellCheck={false} />
          <div>
            <button className="btn btn--primary" onClick={save} disabled={saving}>
              Save
            </button>{" "}
            <button className="btn" onClick={() => setEditing(null)}>
              Cancel
            </button>
          </div>
        </section>
      )}

      <form className="card form-grid" onSubmit={create}>
        <h2 className="span-2">New tenant</h2>
        <label>
          Slug
          <input value={form.slug} onChange={set("slug")} pattern="[a-z0-9]([a-z0-9\-]{0,38}[a-z0-9])?" title="lowercase letters, digits, dashes" required />
        </label>
        <label>
          Name
          <input value={form.name} onChange={set("name")} maxLength={80} required />
        </label>
        <label className="span-2">
          Domain
          <input value={form.domain} onChange={set("domain")} placeholder="shop.example.com" required />
        </label>
        <label>
          First admin name (optional)
          <input value={form.adminName} onChange={set("adminName")} />
        </label>
        <label>
          First admin email
          <input type="email" value={form.adminEmail} onChange={set("adminEmail")} />
        </label>
        <label className="span-2">
          First admin password (12+ characters)
          <input type="password" value={form.adminPassword} onChange={set("adminPassword")} minLength={12} autoComplete="new-password" />
        </label>
        <label className="span-2 platform__check">
          <input type="checkbox" checked={trial} onChange={(e) => setTrial(e.target.checked)} />
          Start as a trial (suspended when the trial ends unless activated)
        </label>
        <button className="btn btn--primary span-2" disabled={creating}>
          Create tenant
        </button>
      </form>

      <PlatformAudit tenants={data?.tenants ?? []} />
    </div>
  );
};

/** Operator console for tenants. Reachable on any domain; it signs in with platform credentials only. */
const Platform = () => {
  const [signedIn, setSignedIn] = useState(() => !!localStorage.getItem(PLATFORM_TOKEN_KEY));
  return signedIn ? <Tenants onSignOut={() => setSignedIn(false)} /> : <Login onDone={() => setSignedIn(true)} />;
};

export default Platform;
