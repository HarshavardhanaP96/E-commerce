import { ReactElement, useState, useMemo } from "react";
import { FaPlus } from "react-icons/fa";
import { Link } from "react-router-dom";
import { Column } from "react-table";
import AdminSidebar from "../../components/admin/AdminSidebar";
import TableHOC from "../../components/admin/TableHOC";

interface DataType {
  photo: ReactElement;
  name: ReactElement; // Changed to ReactElement for Link
  price: number;
  stock: number;
  category: string; // Added for filtering
  // action: ReactElement; // Removed
}

const columns: Column<DataType>[] = [
  {
    Header: "Photo",
    accessor: "photo",
  },
  {
    Header: "Name",
    accessor: "name",
  },
  {
    Header: "Price",
    accessor: "price",
  },
  {
    Header: "Stock",
    accessor: "stock",
  },
  {
      Header: "Category",
      accessor: "category",
  }
];

const img =
  "https://images.unsplash.com/photo-1542291026-7eec264c27ff?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxzZWFyY2h8Mnx8c2hvZXN8ZW58MHx8MHx8&w=1000&q=804";

const img2 = "https://m.media-amazon.com/images/I/514T0SvwkHL._SL1500_.jpg";

const arr: Array<DataType> = [
  {
    photo: <img src={img} alt="Shoes" />,
    name: <Link to="/admin/product/sajknaskd" style={{ fontWeight: 600, color: '#2563eb' }}>Puma Shoes Air Jordan Cook Nigga 2023</Link>,
    price: 690,
    stock: 3,
    category: "Footwear",
  },
  {
    photo: <img src={img2} alt="Shoes" />,
    name: <Link to="/admin/product/sdaskdnkasjdn" style={{ fontWeight: 600, color: '#2563eb' }}>Macbook</Link>,
    price: 232223,
    stock: 213,
    category: "Electronics",
  },
];

const Products = () => {
  const [rows] = useState<DataType[]>(arr);
  const [categoryFilter, setCategoryFilter] = useState<string>("");

  const filteredRows = useMemo(() => {
      if (!categoryFilter) return rows;
      return rows.filter(row => row.category === categoryFilter);
  }, [rows, categoryFilter]);

  const Table = TableHOC<DataType>(
    columns,
    filteredRows,
    "dashboard-product-box",
    "Products",
    filteredRows.length > 6,
    // Extra Controls (Filters + Add Button)
    <div style={{ display: 'flex', gap: '1rem', alignItems: 'center', flex: 1, justifyContent: 'space-between' }}>
        <select 
            value={categoryFilter} 
            onChange={(e) => setCategoryFilter(e.target.value)}
            style={{
                padding: '0.5rem',
                borderRadius: '6px',
                border: '1px solid #d1d5db',
                outline: 'none',
                fontSize: '0.9rem',
                color: '#374151'
            }}
        >
            <option value="">All Categories</option>
            <option value="Footwear">Footwear</option>
            <option value="Electronics">Electronics</option>
        </select>

        <Link to="/admin/product/new" style={{
            display: 'flex',
            alignItems: 'center',
            gap: '0.5rem',
            padding: '0.5rem 1rem',
            backgroundColor: '#ef4444',
            color: 'white',
            borderRadius: '6px',
            textDecoration: 'none',
            fontSize: '0.9rem',
            fontWeight: '600',
            transition: 'background 0.2s'
        }}>
            <FaPlus />
            Create
        </Link>
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

export default Products;
