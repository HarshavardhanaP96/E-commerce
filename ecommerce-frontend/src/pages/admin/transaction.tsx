import { ReactElement, useState, useMemo } from "react";
import { Link } from "react-router-dom";
import { Column } from "react-table";
import AdminSidebar from "../../components/admin/AdminSidebar";
import TableHOC from "../../components/admin/TableHOC";

interface DataType {
  user: ReactElement; // Changed to ReactElement for Link
  amount: number;
  discount: number;
  quantity: number;
  status: ReactElement;
  statusVal: string; // Helper for filtering
  // action: ReactElement; // Removed
}

const arr: Array<DataType> = [
  {
    user: <Link to="/admin/transaction/sajknaskd" style={{ fontWeight: 600, color: '#2563eb' }}>Charas</Link>,
    amount: 4500,
    discount: 400,
    status: <span className="red">Processing</span>,
    statusVal: "Processing",
    quantity: 3,
  },
  {
    user: <Link to="/admin/transaction/sajknaskd" style={{ fontWeight: 600, color: '#2563eb' }}>Xavirors</Link>,
    amount: 6999,
    discount: 400,
    status: <span className="green">Shipped</span>,
    statusVal: "Shipped",
    quantity: 6,
  },
  {
    user: <Link to="/admin/transaction/sajknaskd" style={{ fontWeight: 600, color: '#2563eb' }}>Xavirors</Link>,
    amount: 6999,
    discount: 400,
    status: <span className="purple">Delivered</span>,
    statusVal: "Delivered",
    quantity: 6,
  },
];

const columns: Column<DataType>[] = [
  {
    Header: "User",
    accessor: "user",
  },
  {
    Header: "Amount",
    accessor: "amount",
  },
  {
    Header: "Discount",
    accessor: "discount",
  },
  {
    Header: "Quantity",
    accessor: "quantity",
  },
  {
    Header: "Status",
    accessor: "status",
  },
];

const Transaction = () => {
  const [rows] = useState<DataType[]>(arr);
  const [statusFilter, setStatusFilter] = useState<string>("");

  const filteredRows = useMemo(() => {
    if (!statusFilter) return rows;
    return rows.filter(row => row.statusVal === statusFilter);
  }, [rows, statusFilter]);

  const Table = TableHOC<DataType>(
    columns,
    filteredRows,
    "dashboard-product-box",
    "Transactions",
    filteredRows.length > 6,
    // Extra Controls
     <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
        <select 
            value={statusFilter} 
            onChange={(e) => setStatusFilter(e.target.value)}
            style={{
                padding: '0.5rem',
                borderRadius: '6px',
                border: '1px solid #d1d5db',
                outline: 'none',
                fontSize: '0.9rem',
                color: '#374151'
            }}
        >
            <option value="">All Status</option>
            <option value="Processing">Processing</option>
            <option value="Shipped">Shipped</option>
            <option value="Delivered">Delivered</option>
        </select>
    </div>
  )();

  return (
    <div className="admin-container" style={{ display: 'grid', gridTemplateColumns: 'min-content 1fr', height: 'calc(100vh - 4rem)', backgroundColor: '#f3f4f6' }}>
      <AdminSidebar />
      <main style={{ padding: '1rem', height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
          {Table}
      </main>
    </div>
  );
};

export default Transaction;
