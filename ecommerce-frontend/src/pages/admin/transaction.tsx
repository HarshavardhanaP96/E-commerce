import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import StatusBadge from "../../components/admin/StatusBadge";
import { useAllOrdersQuery } from "../../redux/api/orderAPI";
import { Order } from "../../types/types";
import { formatDate, formatMoney } from "../../utils/features";

type Row = Order & { customer: string; items: number };

const columns: Column<Row>[] = [
  { Header: "Order", accessor: "_id", width: 220 },
  { Header: "Customer", accessor: "customer" },
  { Header: "Items", accessor: "items", width: 80, align: "right" } as Column<Row>,
  { Header: "Discount", accessor: "discount", width: 110, align: "right", Cell: ({ value }: CellProps<Row>) => formatMoney(value) } as Column<Row>,
  { Header: "Amount", accessor: "total", width: 120, align: "right", Cell: ({ value }: CellProps<Row>) => formatMoney(value) } as Column<Row>,
  { Header: "Date", accessor: "createdAt", width: 130, Cell: ({ value }: CellProps<Row>) => formatDate(value) } as Column<Row>,
  { Header: "Status", accessor: "status", width: 120, Cell: ({ value }: CellProps<Row>) => <StatusBadge status={value} /> } as Column<Row>,
];

const Transaction = () => {
  const navigate = useNavigate();
  const [status, setStatus] = useState("");
  const { data, isLoading } = useAllOrdersQuery({ status });

  const rows: Row[] = (data?.orders ?? []).map((o) => ({
    ...o,
    customer: o.user?.name ?? "—",
    items: o.orderItems.reduce((n, i) => n + i.quantity, 0),
  }));

  return (
    <AdminLayout title="Transactions" description="Every order and where it is in delivery.">
      <DataTable
        columns={columns}
        data={rows}
        isLoading={isLoading}
        searchPlaceholder="Search transactions…"
        onRowClick={(o) => navigate(`/admin/transaction/${o._id}`)}
        filters={
          <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
            <option value="">All statuses</option>
            <option value="processing">Processing</option>
            <option value="shipped">Shipped</option>
            <option value="delivered">Delivered</option>
          </select>
        }
      />
    </AdminLayout>
  );
};

export default Transaction;
