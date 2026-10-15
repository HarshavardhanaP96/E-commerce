import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { CellProps, Column } from "react-table";
import { LuUser } from "react-icons/lu";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import { useAllUsersQuery } from "../../redux/api/userAPI";
import { User } from "../../types/types";
import { formatDate, transformImage } from "../../utils/features";

const columns: Column<User>[] = [
  {
    Header: "Avatar",
    accessor: "photo",
    width: 72,
    disableSortBy: true,
    Cell: ({ value, row }: CellProps<User>) =>
      value ? <img className="avatar" src={transformImage(value)} alt={row.original.name} /> : <LuUser />,
  } as Column<User>,
  { Header: "Name", accessor: "name", width: 200 },
  { Header: "Email", accessor: "email" },
  { Header: "Gender", accessor: "gender", width: 110 },
  {
    Header: "Role",
    accessor: "role",
    width: 100,
    Cell: ({ value }: CellProps<User>) => <span className={`badge badge--${value}`}>{value}</span>,
  } as Column<User>,
  { Header: "Joined", accessor: "createdAt", width: 130, Cell: ({ value }: CellProps<User>) => formatDate(value) } as Column<User>,
];

const Customers = () => {
  const navigate = useNavigate();
  const [gender, setGender] = useState("");
  const [role, setRole] = useState("");
  const { data, isLoading } = useAllUsersQuery({ gender, role });

  return (
    <AdminLayout>
      <DataTable
        columns={columns}
        data={data?.users ?? []}
        isLoading={isLoading}
        searchPlaceholder="Search customers…"
        onRowClick={(u) => navigate(`/admin/customer/${u._id}`)}
        filters={
          <>
            <select value={gender} onChange={(e) => setGender(e.target.value)} aria-label="Gender">
              <option value="">All genders</option>
              <option value="male">Male</option>
              <option value="female">Female</option>
            </select>
            <select value={role} onChange={(e) => setRole(e.target.value)} aria-label="Role">
              <option value="">All roles</option>
              <option value="user">User</option>
              <option value="admin">Admin</option>
            </select>
          </>
        }
      />
    </AdminLayout>
  );
};

export default Customers;
