import { useMemo, useState } from "react";
import { LuPlus } from "react-icons/lu";
import { Link, useNavigate } from "react-router-dom";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import { useAdminProductsQuery, useCategoriesQuery } from "../../redux/api/productAPI";
import { Product } from "../../types/types";
import { formatINR, transformImage } from "../../utils/features";

const columns: Column<Product>[] = [
  {
    Header: "Photo",
    accessor: "photo",
    width: 72,
    disableSortBy: true,
    Cell: ({ value, row }: CellProps<Product>) => <img src={transformImage(value)} alt={row.original.name} />,
  } as Column<Product>,
  { Header: "Name", accessor: "name" },
  { Header: "Category", accessor: "category", width: 160 },
  { Header: "Price", accessor: "price", width: 130, Cell: ({ value }: CellProps<Product>) => formatINR(value) } as Column<Product>,
  {
    Header: "Stock",
    accessor: "stock",
    width: 100,
    Cell: ({ value }: CellProps<Product>) => <span className={value > 0 ? "" : "red"}>{value}</span>,
  } as Column<Product>,
];

const Products = () => {
  const navigate = useNavigate();
  const [category, setCategory] = useState("");
  const { data, isLoading } = useAdminProductsQuery();
  const { data: categories } = useCategoriesQuery();

  const rows = useMemo(
    () => (data?.products ?? []).filter((p) => !category || p.category === category),
    [data, category]
  );

  return (
    <AdminLayout>
      <DataTable
        columns={columns}
        data={rows}
        isLoading={isLoading}
        searchPlaceholder="Search products…"
        onRowClick={(p) => navigate(`/admin/product/${p._id}`)}
        filters={
          <select value={category} onChange={(e) => setCategory(e.target.value)} aria-label="Category">
            <option value="">All categories</option>
            {categories?.categories.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        }
        actions={
          <Link to="/admin/product/new" className="btn btn--primary">
            <LuPlus /> Add product
          </Link>
        }
      />
    </AdminLayout>
  );
};

export default Products;
