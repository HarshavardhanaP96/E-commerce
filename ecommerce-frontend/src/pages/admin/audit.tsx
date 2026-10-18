import { useMemo, useState } from "react";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import { errorMessage } from "../../redux/api/baseQuery";
import { AUDIT_ACTIONS, AuditChange, AuditEntry, useAuditLogQuery } from "../../redux/api/auditAPI";

const ENTITY_TYPES = ["product", "category", "coupon", "order", "user", "address"];

type Row = AuditEntry & { actor: string; entity: string; summary: string };

const show = (value: unknown) =>
  value === null || value === undefined ? "—" : Array.isArray(value) ? value.join(", ") : String(value);

const summarise = (changes: AuditChange[]) =>
  changes
    .map((c) => (c.redacted ? `${c.field} changed` : `${c.field}: ${show(c.before)} → ${show(c.after)}`))
    .join("; ");

const columns: Column<Row>[] = [
  { Header: "When", accessor: "at", width: 170, Cell: ({ value }: CellProps<Row>) => new Date(value).toLocaleString("en-IN") } as Column<Row>,
  { Header: "Who", accessor: "actor", width: 190 },
  { Header: "Action", accessor: "action", width: 210 },
  { Header: "Record", accessor: "entity", width: 230 },
  { Header: "Result", accessor: "outcome", width: 100 },
  { Header: "Changes", accessor: "summary" },
];

const startOfDay = (date: string) => (date ? new Date(`${date}T00:00:00`).toISOString() : "");
const endOfDay = (date: string) => (date ? new Date(`${date}T23:59:59.999`).toISOString() : "");

const AuditLog = () => {
  const [entityType, setEntityType] = useState("");
  const [action, setAction] = useState("");
  const [outcome, setOutcome] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(1);

  // currentData (not data) so rows from a previous filter are never shown for the current one.
  const { currentData, isLoading, isFetching, isError, error } = useAuditLogQuery({
    entityType,
    action,
    outcome,
    from: startOfDay(from),
    to: endOfDay(to),
    page,
  });

  const rows: Row[] = useMemo(
    () =>
      (currentData?.entries ?? []).map((e) => ({
        ...e,
        actor: e.actorId ? `${e.actorRole} ${e.actorId.slice(-6)}` : e.actorRole,
        entity: e.entityType ? `${e.entityType} ${e.entityId ?? ""}`.trim() : "—",
        summary: summarise(e.changes),
      })),
    [currentData]
  );
  const totalPage = currentData?.totalPage ?? 1;
  const failure = isError ? errorMessage(error, "Could not load the audit log") : null;

  // Changing a filter returns to the first page.
  const filter = (set: (value: string) => void) => (value: string) => {
    set(value);
    setPage(1);
  };

  return (
    <AdminLayout>
      {failure && (
        <p className="red" role="alert">
          {failure}
        </p>
      )}
      <DataTable
        resetKey={`${entityType}|${action}|${outcome}|${from}|${to}|${page}`}
        columns={columns}
        data={rows}
        isLoading={isLoading || isFetching}
        searchPlaceholder="Search this page…"
        emptyText={failure ?? "No audit entries match these filters"}
        filters={
          <>
            <select value={entityType} onChange={(e) => filter(setEntityType)(e.target.value)} aria-label="Record type">
              <option value="">All records</option>
              {ENTITY_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
            <select value={action} onChange={(e) => filter(setAction)(e.target.value)} aria-label="Action">
              <option value="">All actions</option>
              {AUDIT_ACTIONS.map((a) => (
                <option key={a} value={a}>{a}</option>
              ))}
            </select>
            <select value={outcome} onChange={(e) => filter(setOutcome)(e.target.value)} aria-label="Result">
              <option value="">Any result</option>
              <option value="SUCCESS">Success</option>
              <option value="FAILURE">Failure</option>
            </select>
            <input type="date" value={from} onChange={(e) => filter(setFrom)(e.target.value)} aria-label="From date" />
            <input type="date" value={to} onChange={(e) => filter(setTo)(e.target.value)} aria-label="To date" />
          </>
        }
        actions={
          <>
            <button type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>Newer</button>
            <span aria-live="polite">Page {page} of {Math.max(totalPage, 1)}</span>
            <button type="button" disabled={page >= totalPage} onClick={() => setPage(page + 1)}>Older</button>
          </>
        }
      />
    </AdminLayout>
  );
};

export default AuditLog;
