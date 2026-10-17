import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { LuPause, LuPlay, LuTrash2 } from "react-icons/lu";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../../components/admin/AdminLayout";
import DataTable from "../../../components/admin/DataTable";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import {
  useAllCouponsQuery,
  useDeleteCouponMutation,
  useNewCouponMutation,
  useUpdateCouponMutation,
} from "../../../redux/api/couponAPI";
import { Coupon as CouponType, NewCouponInput } from "../../../types/types";
import { formatDate, formatINR } from "../../../utils/features";

const randomCode = () =>
  Array.from(crypto.getRandomValues(new Uint8Array(8)))
    .map((b) => "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[b % 32])
    .join("");

type Form = {
  code: string;
  type: "FLAT" | "PERCENT";
  amount: string;
  maxDiscount: string;
  minOrderValue: string;
  startsAt: string;
  expiresAt: string;
  usageLimit: string;
  perUserLimit: string;
};

const emptyForm: Form = {
  code: "",
  type: "FLAT",
  amount: "",
  maxDiscount: "",
  minOrderValue: "",
  startsAt: "",
  expiresAt: "",
  usageLimit: "",
  perUserLimit: "",
};

const num = (v: string) => (v === "" ? undefined : Number(v));
const iso = (local: string) => (local ? new Date(local).toISOString() : undefined);

const describe = (c: CouponType) =>
  c.type === "PERCENT"
    ? `${c.amount}% off${c.maxDiscount ? ` (max ${formatINR(c.maxDiscount)})` : ""}`
    : `${formatINR(c.amount)} off`;

const Coupon = () => {
  const { data, isLoading } = useAllCouponsQuery();
  const [newCoupon, { isLoading: creating }] = useNewCouponMutation();
  const [updateCoupon] = useUpdateCouponMutation();
  const [deleteCoupon] = useDeleteCouponMutation();
  const [form, setForm] = useState<Form>(emptyForm);
  const set = (field: keyof Form) => (e: { target: { value: string } }) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const columns: Column<CouponType>[] = [
    { Header: "Code", accessor: "coupon" },
    { Header: "Discount", accessor: "amount", width: 190, Cell: ({ row }: CellProps<CouponType>) => describe(row.original) } as Column<CouponType>,
    { Header: "Min order", accessor: "minOrderValue", width: 110, Cell: ({ value }: CellProps<CouponType>) => (value ? formatINR(value) : "—") } as Column<CouponType>,
    {
      Header: "Valid",
      id: "valid",
      width: 190,
      disableSortBy: true,
      Cell: ({ row }: CellProps<CouponType>) =>
        row.original.startsAt || row.original.expiresAt
          ? `${row.original.startsAt ? formatDate(row.original.startsAt) : "now"} → ${row.original.expiresAt ? formatDate(row.original.expiresAt) : "no end"}`
          : "Always",
    } as Column<CouponType>,
    {
      Header: "Used",
      accessor: "usedCount",
      width: 100,
      Cell: ({ row }: CellProps<CouponType>) =>
        row.original.usageLimit ? `${row.original.usedCount} / ${row.original.usageLimit}` : String(row.original.usedCount),
    } as Column<CouponType>,
    { Header: "Status", accessor: "status", width: 110, Cell: ({ value }: CellProps<CouponType>) => <StatusBadge status={value} /> } as Column<CouponType>,
    {
      Header: "",
      id: "actions",
      width: 100,
      disableSortBy: true,
      Cell: ({ row }: CellProps<CouponType>) => (
        <>
          <button
            className="icon-btn"
            aria-label={row.original.active ? `Pause ${row.original.coupon}` : `Activate ${row.original.coupon}`}
            onClick={async () => {
              const res = await updateCoupon({ id: row.original._id, body: { active: !row.original.active } });
              if ("error" in res) toast.error(errorMessage(res.error));
            }}
          >
            {row.original.active ? <LuPause /> : <LuPlay />}
          </button>
          <button
            className="icon-btn icon-btn--danger"
            aria-label={`Delete ${row.original.coupon}`}
            onClick={async () => {
              if (!confirm(`Delete coupon ${row.original.coupon}?`)) return;
              const res = await deleteCoupon(row.original._id);
              if ("error" in res) toast.error(errorMessage(res.error));
              else toast.success("Coupon deleted");
            }}
          >
            <LuTrash2 />
          </button>
        </>
      ),
    } as Column<CouponType>,
  ];

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const body: NewCouponInput = {
      coupon: form.code.trim(),
      amount: Number(form.amount),
      type: form.type,
      maxDiscount: form.type === "PERCENT" ? num(form.maxDiscount) : undefined,
      minOrderValue: num(form.minOrderValue),
      startsAt: iso(form.startsAt),
      expiresAt: iso(form.expiresAt),
      usageLimit: num(form.usageLimit),
      perUserLimit: num(form.perUserLimit),
    };
    const res = await newCoupon(body);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    setForm(emptyForm);
  };

  return (
    <AdminLayout>
      <DataTable
        columns={columns}
        data={data?.coupons ?? []}
        isLoading={isLoading}
        searchPlaceholder="Search coupons…"
        actions={
          <details className="coupon-form">
            <summary className="btn btn--primary">New coupon</summary>
            <form className="card form-grid" onSubmit={submit}>
              <label>
                Code
                <input
                  value={form.code}
                  onChange={(e) => setForm((p) => ({ ...p, code: e.target.value.toUpperCase() }))}
                  pattern="[A-Za-z0-9_\-]{3,32}"
                  title="3-32 letters, digits, _ or -"
                  required
                />
              </label>
              <button type="button" className="btn" onClick={() => setForm((p) => ({ ...p, code: randomCode() }))}>
                Generate
              </button>
              <label>
                Type
                <select value={form.type} onChange={set("type")}>
                  <option value="FLAT">Flat (₹ off)</option>
                  <option value="PERCENT">Percent (% off)</option>
                </select>
              </label>
              <label>
                {form.type === "PERCENT" ? "Percent off (1–100)" : "Amount off (₹)"}
                <input type="number" min={1} max={form.type === "PERCENT" ? 100 : 100000} step="0.01"
                       value={form.amount} onChange={set("amount")} required />
              </label>
              {form.type === "PERCENT" && (
                <label>
                  Max discount (₹, optional)
                  <input type="number" min={1} value={form.maxDiscount} onChange={set("maxDiscount")} />
                </label>
              )}
              <label>
                Minimum order (₹, optional)
                <input type="number" min={0} value={form.minOrderValue} onChange={set("minOrderValue")} />
              </label>
              <label>
                Starts (optional)
                <input type="datetime-local" value={form.startsAt} onChange={set("startsAt")} />
              </label>
              <label>
                Expires (optional)
                <input type="datetime-local" value={form.expiresAt} onChange={set("expiresAt")} />
              </label>
              <label>
                Total uses (optional)
                <input type="number" min={1} value={form.usageLimit} onChange={set("usageLimit")} />
              </label>
              <label>
                Uses per customer (optional)
                <input type="number" min={1} value={form.perUserLimit} onChange={set("perUserLimit")} />
              </label>
              <button type="submit" className="btn btn--primary span-2" disabled={creating}>
                Create coupon
              </button>
            </form>
          </details>
        }
      />
    </AdminLayout>
  );
};

export default Coupon;
