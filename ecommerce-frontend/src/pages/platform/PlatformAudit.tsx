import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import PageHeader from "../../components/admin/PageHeader";
import { AUDIT_ACTIONS, summariseChanges } from "../../redux/api/auditAPI";
import { errorMessage } from "../../redux/api/baseQuery";
import { usePlatformAuditQuery, useTenantsQuery } from "../../redux/api/platformAPI";
import { PlatformShell } from "./PlatformSidebar";

/** Read-only: every store's audit log, newest first. Platform actions appear here and in the store's own log. */
const PlatformAudit = () => {
  const { data: list } = useTenantsQuery();
  const tenants = list?.tenants ?? [];
  const [params, setParams] = useSearchParams();
  const tenantId = params.get("tenant") ?? "";
  const [action, setAction] = useState("");
  const [page, setPage] = useState(1);
  const { data, isFetching, isError, error } = usePlatformAuditQuery({ tenantId, action, page });
  const storeName = (id: string | null) => tenants.find((t) => t.id === id)?.slug ?? id ?? "—";
  const totalPage = data?.totalPage ?? 0;

  return (
    <PlatformShell>
      <PageHeader title="Audit log" description="Every store's log, newest first. Read-only." />
      <section className="card platform-scroll">
      <div className="platform-filters">
        <select value={tenantId} onChange={(e) => {
            setParams(e.target.value ? { tenant: e.target.value } : {});
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
      <table className="platform-table">
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
        <div className="platform-filters">
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
    </PlatformShell>
  );
};

export default PlatformAudit;
