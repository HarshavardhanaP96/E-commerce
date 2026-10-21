import { useMemo, useState } from "react";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import { errorMessage } from "../../redux/api/baseQuery";
import { AUDIT_ACTIONS, AuditEntry, summariseChanges, useAuditLogQuery } from "../../redux/api/auditAPI";

const ENTITY_TYPES = ["product", "category", "coupon", "order", "user", "address", "tenant", "report"];

type Row = AuditEntry & { actor: string; entity: string; summary: string };

// Widths are tuned so the Changes column is visible at a 1280px viewport; the Record cell shows the type and
// id in full in its tooltip (DataTable puts the cell value in the title; for When that is the exact ISO time) and is clipped with an ellipsis.
const columns: Column<Row>[] = [
  { Header: "When", accessor: "at", width: 130, Cell: ({ value }: CellProps<Row>) => new Date(value).toLocaleString("en-IN", { dateStyle: "short", timeStyle: "short" }) } as Column<Row>,
  { Header: "Who", accessor: "actor", width: 130 },
  { Header: "Action", accessor: "action", width: 200 },
  {
    Header: "Record",
    accessor: "entity",
    width: 110,
    Cell: ({ value }: CellProps<Row>) => (
      <span style={{ display: "inline-block", maxWidth: 110, overflow: "hidden", textOverflow: "ellipsis", verticalAlign: "middle" }}>
        {value}
      </span>
    ),
  } as Column<Row>,
  { Header: "Result", accessor: "outcome", width: 96 },
  { Header: "Changes", accessor: "summary" },
];

// A half-typed year gives an invalid Date, and toISOString() would throw during render; treat it as "no bound".
const toIso = (date: string, time: string) => {
  if (!date) return "";
  const d = new Date(`${date}T${time}`);
  return Number.isNaN(d.getTime()) ? "" : d.toISOString();
};
const startOfDay = (date: string) => toIso(date, "00:00:00");
const endOfDay = (date: string) => toIso(date, "23:59:59.999");

const AuditLog = () => {
  const [entityType, setEntityType] = useState("");
  const [entityId, setEntityId] = useState("");
  const [actorId, setActorId] = useState("");
  const [action, setAction] = useState("");
  const [outcome, setOutcome] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10); // the table's default rows per page

  // currentData (not data) so rows from a previous filter are never shown for the current one.
  const { currentData, isLoading, isFetching, isError, error } = useAuditLogQuery({
    entityType,
    entityId: entityId.trim(),
    actorId: actorId.trim(),
    action,
    outcome,
    from: startOfDay(from),
    to: endOfDay(to),
    page,
    limit: pageSize,
  });

  const rows: Row[] = useMemo(
    () =>
      (currentData?.entries ?? []).map((e) => ({
        ...e,
        actor: e.actorId ? `${e.actorRole} ${e.actorId.slice(-6)}` : e.actorRole,
        entity: e.entityType ? `${e.entityType} ${e.entityId ?? ""}`.trim() : "—",
        summary: summariseChanges(e.changes),
      })),
    [currentData]
  );
  const failure = isError ? errorMessage(error, "Could not load the audit log") : null;

  // Changing a filter returns to the first page.
  const filter = (set: (value: string) => void) => (value: string) => {
    set(value);
    setPage(1);
  };

  return (
    <AdminLayout title="Audit log" description="Who changed what in your store, and when.">
      {failure && (
        <p className="red" role="alert">
          {failure}
        </p>
      )}
      <DataTable
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
            <input
              type="text"
              value={entityId}
              onChange={(e) => filter(setEntityId)(e.target.value)}
              placeholder="Record id"
              aria-label="Record id"
            />
            <input
              type="text"
              value={actorId}
              onChange={(e) => filter(setActorId)(e.target.value)}
              placeholder="Actor id"
              aria-label="Actor id"
            />
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
        serverPaging={{
          page,
          pageSize,
          total: currentData?.total ?? 0,
          onPageChange: setPage,
          onPageSizeChange: (size) => {
            setPageSize(size);
            setPage(1);
          },
        }}
      />
    </AdminLayout>
  );
};

export default AuditLog;
