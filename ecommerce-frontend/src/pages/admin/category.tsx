import { FormEvent, useMemo, useState } from "react";
import toast from "react-hot-toast";
import { LuImage, LuPencil, LuTrash2 } from "react-icons/lu";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import { errorMessage } from "../../redux/api/baseQuery";
import {
  useCategoryTreeQuery,
  useDeleteCategoryMutation,
  useNewCategoryMutation,
  useUpdateCategoryMutation,
} from "../../redux/api/categoryAPI";
import { Category } from "../../types/types";
import { formatDate, transformImage } from "../../utils/features";

const MAX_BYTES = 5 * 1024 * 1024;

type Row = Category & { path: string };

/** "Clothing › Shirts" for a nested category; the chain is cut off if the data ever loops. */
const pathOf = (c: Category, byId: Map<string, Category>) => {
  const names = [c.name];
  let cursor = c.parentId ? byId.get(c.parentId) : undefined;
  for (let hops = 0; cursor && hops < 20; hops++) {
    names.unshift(cursor.name);
    cursor = cursor.parentId ? byId.get(cursor.parentId) : undefined;
  }
  return names.join(" › ");
};

/** The category and everything below it, so the parent picker can't offer a loop. */
const descendantsOf = (id: string, all: Category[]) => {
  const out = new Set<string>([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const c of all) {
      if (c.parentId && out.has(c.parentId) && !out.has(c._id)) {
        out.add(c._id);
        grew = true;
      }
    }
  }
  return out;
};

const CategoryAdmin = () => {
  const { data, isLoading } = useCategoryTreeQuery();
  const [createCategory, { isLoading: creating }] = useNewCategoryMutation();
  const [updateCategory, { isLoading: updating }] = useUpdateCategoryMutation();
  const [deleteCategory] = useDeleteCategoryMutation();

  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Category | null>(null);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  const [image, setImage] = useState<File | undefined>();

  const rows: Row[] = useMemo(() => {
    const all = data?.categories ?? [];
    const byId = new Map(all.map((c) => [c._id, c]));
    return all.map((c) => ({ ...c, path: pathOf(c, byId) })).sort((a, b) => a.path.localeCompare(b.path));
  }, [data]);

  const parentChoices = useMemo(() => {
    const all = data?.categories ?? [];
    const blocked = editing ? descendantsOf(editing._id, all) : new Set<string>();
    return rows.filter((r) => !blocked.has(r._id));
  }, [data, rows, editing]);

  const close = () => {
    setOpen(false);
    setEditing(null);
    setName("");
    setParentId("");
    setImage(undefined);
  };

  const startEdit = (c: Category) => {
    setEditing(c);
    setName(c.name);
    setParentId(c.parentId ?? "");
    setImage(undefined);
    setOpen(true);
  };

  const onImage = (file?: File) => {
    if (file && file.size > MAX_BYTES) return toast.error("Image must be 5 MB or smaller");
    setImage(file);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const fd = new FormData();
    fd.set("name", name.trim());
    if (parentId) fd.set("parentId", parentId);
    if (image) fd.set("image", image);
    const res = editing ? await updateCategory({ id: editing._id, formData: fd }) : await createCategory(fd);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    close();
  };

  const columns: Column<Row>[] = [
    {
      Header: "Image",
      accessor: "image",
      width: 72,
      disableSortBy: true,
      Cell: ({ value, row }: CellProps<Row>) =>
        value ? <img className="avatar" src={transformImage(value)} alt={row.original.name} /> : <LuImage aria-hidden />,
    } as Column<Row>,
    { Header: "Category", accessor: "path" },
    { Header: "Slug", accessor: "slug", width: 200 },
    { Header: "Created", accessor: "createdAt", width: 130, Cell: ({ value }: CellProps<Row>) => formatDate(value) } as Column<Row>,
    {
      Header: "",
      id: "actions",
      width: 110,
      disableSortBy: true,
      Cell: ({ row }: CellProps<Row>) => (
        <>
          <button className="icon-btn" aria-label={`Edit ${row.original.name}`} onClick={() => startEdit(row.original)}>
            <LuPencil />
          </button>
          <button
            className="icon-btn icon-btn--danger"
            aria-label={`Delete ${row.original.name}`}
            onClick={async () => {
              if (!confirm(`Delete category "${row.original.name}"?`)) return;
              const res = await deleteCategory(row.original._id);
              if ("error" in res) toast.error(errorMessage(res.error));
              else toast.success(res.data.message);
            }}
          >
            <LuTrash2 />
          </button>
        </>
      ),
    } as Column<Row>,
  ];

  return (
    <AdminLayout>
      <DataTable
        columns={columns}
        data={rows}
        isLoading={isLoading}
        searchPlaceholder="Search categories…"
        emptyText="No categories yet. Create one, then link products to it from the product page."
        actions={
          <details
            className="coupon-form"
            open={open}
            onToggle={(e) => {
              if (!e.currentTarget.open && open) close();
              else setOpen(e.currentTarget.open);
            }}
          >
            <summary className="btn btn--primary">New category</summary>
            <form className="card form-grid" onSubmit={submit}>
              <h4 className="span-2">{editing ? `Edit ${editing.name}` : "New category"}</h4>
              <label className="span-2">
                Name
                <input value={name} onChange={(e) => setName(e.target.value)} maxLength={80} required />
              </label>
              <label className="span-2">
                Parent category
                <select value={parentId} onChange={(e) => setParentId(e.target.value)}>
                  <option value="">{editing?.parentId ? "Keep current parent" : "None (top level)"}</option>
                  {parentChoices.map((c) => (
                    <option key={c._id} value={c._id}>{c.path}</option>
                  ))}
                </select>
              </label>
              <label className="span-2">
                Image (JPEG, PNG, WEBP or GIF, max 5 MB{editing ? "; leave empty to keep the current one" : ""})
                <input type="file" accept="image/jpeg,image/png,image/webp,image/gif"
                       onChange={(e) => onImage(e.target.files?.[0])} />
              </label>
              <button type="submit" className="btn btn--primary" disabled={creating || updating}>
                {editing ? "Save changes" : "Create category"}
              </button>
              <button type="button" className="btn" onClick={close}>Cancel</button>
            </form>
          </details>
        }
      />
    </AdminLayout>
  );
};

export default CategoryAdmin;
