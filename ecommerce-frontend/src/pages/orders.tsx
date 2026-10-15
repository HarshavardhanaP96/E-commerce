import { useNavigate } from "react-router-dom";
import { CellProps, Column } from "react-table";
import DataTable from "../components/admin/DataTable";
import StatusBadge from "../components/admin/StatusBadge";
import { useMyOrdersQuery } from "../redux/api/orderAPI";
import { Order } from "../types/types";
import { formatDate, formatINR } from "../utils/features";

type Row = Order & { items: number };

const columns: Column<Row>[] = [
  { Header: "Order", accessor: "_id", width: 220 },
  { Header: "Date", accessor: "createdAt", Cell: ({ value }: CellProps<Row>) => formatDate(value) } as Column<Row>,
  { Header: "Items", accessor: "items", width: 80 },
  { Header: "Discount", accessor: "discount", Cell: ({ value }: CellProps<Row>) => formatINR(value) } as Column<Row>,
  { Header: "Amount", accessor: "total", Cell: ({ value }: CellProps<Row>) => formatINR(value) } as Column<Row>,
  { Header: "Status", accessor: "status", width: 120, Cell: ({ value }: CellProps<Row>) => <StatusBadge status={value} /> } as Column<Row>,
];

const Orders = () => {
  const navigate = useNavigate();
  const { data, isLoading } = useMyOrdersQuery();
  const rows: Row[] = (data?.orders ?? []).map((o) => ({ ...o, items: o.orderItems.reduce((n, i) => n + i.quantity, 0) }));

  return (
    <div className="container orders-page">
      <h1>My Orders</h1>
      <DataTable
        columns={columns}
        data={rows}
        isLoading={isLoading}
        searchPlaceholder="Search orders…"
        emptyText="You have not placed any orders yet"
        onRowClick={(o) => navigate(`/order/${o._id}`)}
      />
    </div>
  );
};

export default Orders;
