import { Link } from "react-router-dom";
import StatusBadge from "../../../components/admin/StatusBadge";
import { summariseChanges } from "../../../redux/api/auditAPI";
import { TenantDetails, usePlatformAuditQuery } from "../../../redux/api/platformAPI";
import { statusNote } from "../storeList";
import { formatBytes, usagePercent } from "../workspaceForms";

const Meter = ({ label, used, limit, format }: { label: string; used: number; limit: number; format: (n: number) => string }) => {
  const pct = usagePercent(used, limit);
  return (
    <div className="meter">
      <div className="meter__head">
        <span>{label}</span>
        <span>
          {format(used)} of {format(limit)}
        </span>
      </div>
      <progress max={100} value={pct} aria-label={`${label}: ${pct}% used`} />
    </div>
  );
};

/** Status, usage against limits, the owner's own rules (read-only) and the latest activity. */
const OverviewTab = ({ details }: { details: TenantDetails }) => {
  const { tenant, settings, usage, defaults } = details;
  const { data: audit } = usePlatformAuditQuery({ tenantId: tenant.id, page: 1 });
  const limits = {
    products: tenant.limits?.products ?? defaults.limits.products,
    uploadBytes: tenant.limits?.uploadBytes ?? defaults.limits.uploadBytes,
  };
  const c = settings.commerce;
  const rule = (v: number | string | null, unit = "") => (v === null ? "Platform default" : `${v}${unit}`);

  return (
    <div className="overview">
      <section className="card">
        <h3>Status</h3>
        <p>
          <StatusBadge status={tenant.status} /> {statusNote(tenant)}
        </p>
        <p className="platform-muted">Domains: {tenant.domains.join(", ") || "—"}</p>
      </section>

      <section className="card">
        <h3>Usage</h3>
        {usage ? (
          <>
            <Meter label="Products" used={usage.products} limit={limits.products} format={String} />
            <Meter label="Photo storage" used={usage.uploadBytes} limit={limits.uploadBytes} format={formatBytes} />
          </>
        ) : (
          <p className="platform-muted">Usage unavailable.</p>
        )}
      </section>

      <section className="card">
        <h3>Owner&apos;s shop rules</h3>
        <dl className="overview__rules">
          <dt>Default GST</dt>
          <dd>{rule(c.defaultTaxRate, "%")}</dd>
          <dt>Shipping</dt>
          <dd>
            {rule(c.shippingFee)}
            {c.freeShippingAbove !== null ? `, free above ${c.freeShippingAbove}` : ""}
          </dd>
          <dt>Order prefix</dt>
          <dd>{rule(c.orderPrefix)}</dd>
          <dt>Time zone</dt>
          <dd>{rule(settings.regional.timeZone)}</dd>
        </dl>
        <p className="platform-muted">Set by the store owner in Store settings.</p>
      </section>

      <section className="card overview__activity">
        <h3>Recent activity</h3>
        <ul>
          {(audit?.entries ?? []).slice(0, 10).map((e) => (
            <li key={e._id}>
              <time dateTime={e.at}>{new Date(e.at).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</time>{" "}
              <strong>{e.action}</strong> <span className="platform-muted">{summariseChanges(e.changes)}</span>
            </li>
          ))}
          {audit && audit.entries.length === 0 && <li className="platform-muted">No activity yet.</li>}
        </ul>
        <Link to={`/platform/audit?tenant=${tenant.id}`}>View all</Link>
      </section>
    </div>
  );
};

export default OverviewTab;
