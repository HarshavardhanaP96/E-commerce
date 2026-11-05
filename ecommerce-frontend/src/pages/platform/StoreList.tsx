import { useMemo, useState } from "react";
import { LuPlus } from "react-icons/lu";
import { Link, useNavigate } from "react-router-dom";
import { CellProps, Column } from "react-table";
import DataTable from "../../components/admin/DataTable";
import PageHeader from "../../components/admin/PageHeader";
import StatusBadge from "../../components/admin/StatusBadge";
import { errorMessage } from "../../redux/api/baseQuery";
import { PlatformTenant, useTenantsQuery } from "../../redux/api/platformAPI";
import { PlatformShell } from "./PlatformSidebar";
import { byChip, CHIPS, domainSummary, StatusChip, statusNote, typeLabel } from "./storeList";

// `store` and `domains` hold every searchable word (name + slug, all domains): the table's search matches cell values.
type Row = { slug: string; name: string; store: string; type: string; domains: string; status: string; note: string | null; created: string };

const columns: Column<Row>[] = [
  {
    Header: "Store",
    accessor: "store",
    Cell: ({ row }: CellProps<Row>) => (
      <span>
        <strong>{row.original.name}</strong> <small className="platform-muted">{row.original.slug}</small>
      </span>
    ),
  },
  { Header: "Type", accessor: "type" },
  {
    Header: "Domains",
    accessor: "domains",
    Cell: ({ value }: CellProps<Row, string>) => <>{domainSummary(value ? value.split(" ") : [])}</>,
  },
  {
    Header: "Status",
    accessor: "status",
    Cell: ({ row }: CellProps<Row>) => (
      <span>
        <StatusBadge status={row.original.status} />
        {row.original.note && <small className="platform-muted platform-note">{row.original.note}</small>}
      </span>
    ),
  },
  {
    Header: "Created",
    accessor: "created", // ISO, so it sorts by date; shown in en-IN
    Cell: ({ value }: CellProps<Row, string>) => (
      <>{value ? new Date(value).toLocaleDateString("en-IN", { dateStyle: "medium" }) : "—"}</>
    ),
  },
];

const toRow = (t: PlatformTenant): Row => ({
  slug: t.slug,
  name: t.name,
  store: `${t.name} ${t.slug}`,
  type: typeLabel(t.type),
  domains: t.domains.join(" "),
  status: t.status,
  note: statusNote(t),
  created: t.createdAt ?? "",
});

/** Every store: search, status chips and sort run in the browser (one call). Click a row to open the store. */
const StoreList = () => {
  const { data, isLoading, isError, error } = useTenantsQuery();
  const [chip, setChip] = useState<StatusChip>("all");
  const navigate = useNavigate();
  const rows = useMemo(() => byChip(data?.tenants ?? [], chip).map(toRow), [data, chip]);

  return (
    <PlatformShell>
      <PageHeader
        title="Stores"
        description="Every store on the platform. Open one to configure it."
        actions={
          <Link className="btn btn--primary" to="/platform/stores/new">
            <LuPlus aria-hidden /> Onboard store
          </Link>
        }
      />
      {isError && <p role="alert">{errorMessage(error)}</p>}
      <DataTable<Row>
        columns={columns}
        data={rows}
        isLoading={isLoading}
        searchPlaceholder="Search by name, slug or domain"
        resetKey={chip}
        onRowClick={(r) => navigate(`/platform/stores/${r.slug}`)}
        emptyText="No stores match"
        filters={
          <div className="chips" role="group" aria-label="Status">
            {CHIPS.map((c) => (
              <button
                key={c.key}
                type="button"
                className={`chip${chip === c.key ? " is-on" : ""}`}
                aria-pressed={chip === c.key}
                onClick={() => setChip(c.key)}
              >
                {c.label}
              </button>
            ))}
          </div>
        }
      />
    </PlatformShell>
  );
};

export default StoreList;
