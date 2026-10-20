import { useMemo, useState } from "react";
import { LuPlus } from "react-icons/lu";
import { Link, useNavigate } from "react-router-dom";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import ProductCsvTools from "../../components/admin/ProductCsvTools";
import { useAdminProductsQuery, useCategoriesQuery } from "../../redux/api/productAPI";
import { Product } from "../../types/types";
import { formatMoney, transformImage } from "../../utils/features";

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
  { Header: "Price", accessor: "price", width: 130, Cell: ({ value }: CellProps<Product>) => formatMoney(value) } as Column<Product>,
  {
    Header: "Stock",
    accessor: "stock",
    width: 140,
    Cell: ({ value, row }: CellProps<Product>) => {
      const p = row.original;
      const at = p.lowStockAt ?? 5;
      const active = (p.variants?.items ?? []).filter((v) => v.status === "ACTIVE");
      const low = active.length ? active.filter((v) => v.stock <= at).length : value <= at ? 1 : 0;
      return (
        <span className="stock-cell">
          <span className={value > 0 ? "" : "red"}>{value}</span>
          {low > 0 && (
            <span className="badge badge--processing" title={`At or below ${at}`}>
              {active.length ? `${low} low` : "Low"}
            </span>
          )}
        </span>
      );
    },
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
          <>
            <ProductCsvTools />
            <Link to="/admin/product/new" className="btn btn--primary">
              <LuPlus /> Add product
            </Link>
          </>
        }
      />
    </AdminLayout>
  );
};

export default Products;
