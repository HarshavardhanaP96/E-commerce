import { ReactElement, useState, useMemo } from "react";
// import { FaTrash } from "react-icons/fa";
import { Link } from "react-router-dom";
import { Column } from "react-table";
import AdminSidebar from "../../components/admin/AdminSidebar";
import TableHOC from "../../components/admin/TableHOC";

interface DataType {
  avatar: ReactElement;
  name: ReactElement; // Changed for Link
  email: string;
  gender: string;
  role: string;
  // action: ReactElement; // Removed
}

const columns: Column<DataType>[] = [
  {
    Header: "Avatar",
    accessor: "avatar",
  },
  {
    Header: "Name",
    accessor: "name",
  },
  {
    Header: "Gender",
    accessor: "gender",
  },
  {
    Header: "Email",
    accessor: "email",
  },
  {
    Header: "Role",
    accessor: "role",
  },
];

const img = "https://randomuser.me/api/portraits/women/54.jpg";
const img2 = "https://randomuser.me/api/portraits/women/50.jpg";

const arr: Array<DataType> = [
  {
    avatar: (
      <img
        style={{
          borderRadius: "50%",
        }}
        src={img}
        alt="Shoes"
      />
    ),
    name: <Link to="/admin/customer/sajknaskd" style={{ fontWeight: 600, color: '#2563eb' }}>Emily Palmer</Link>,
    email: "emily.palmer@example.com",
    gender: "female",
    role: "user",
  },

  {
    avatar: (
      <img
        style={{
          borderRadius: "50%",
        }}
        src={img2}
        alt="Shoes"
      />
    ),
    name: <Link to="/admin/customer/sajknaskd" style={{ fontWeight: 600, color: '#2563eb' }}>May Scoot</Link>,
    email: "aunt.may@example.com",
    gender: "female",
    role: "admin",
  },
];

const Customers = () => {
  const [rows] = useState<DataType[]>(arr);
  const [genderFilter, setGenderFilter] = useState<string>("");
  const [roleFilter, setRoleFilter] = useState<string>("");

  const filteredRows = useMemo(() => {
    let tempRows = [...rows];
    if (genderFilter) {
        tempRows = tempRows.filter(row => row.gender === genderFilter);
    }
    if (roleFilter) {
        tempRows = tempRows.filter(row => row.role === roleFilter);
    }
    return tempRows;
  }, [rows, genderFilter, roleFilter]);

  const Table = TableHOC<DataType>(
    columns,
    filteredRows,
    "dashboard-product-box",
    "Customers",
    filteredRows.length > 6,
    // Extra Controls (Filters)
    <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
        <select 
            value={genderFilter} 
            onChange={(e) => setGenderFilter(e.target.value)}
            style={{
                padding: '0.5rem',
                borderRadius: '6px',
                border: '1px solid #d1d5db',
                outline: 'none',
                fontSize: '0.9rem',
                color: '#374151'
            }}
        >
            <option value="">All Genders</option>
            <option value="male">Male</option>
            <option value="female">Female</option>
        </select>

        <select 
            value={roleFilter} 
            onChange={(e) => setRoleFilter(e.target.value)}
            style={{
                padding: '0.5rem',
                borderRadius: '6px',
                border: '1px solid #d1d5db',
                outline: 'none',
                fontSize: '0.9rem',
                color: '#374151'
            }}
        >
            <option value="">All Roles</option>
            <option value="user">User</option>
            <option value="admin">Admin</option>
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

export default Customers;
