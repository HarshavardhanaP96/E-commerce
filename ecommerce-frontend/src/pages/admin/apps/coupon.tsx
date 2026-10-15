import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { LuTrash2 } from "react-icons/lu";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../../components/admin/AdminLayout";
import DataTable from "../../../components/admin/DataTable";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useAllCouponsQuery, useDeleteCouponMutation, useNewCouponMutation } from "../../../redux/api/couponAPI";
import { Coupon as CouponType } from "../../../types/types";
import { formatINR } from "../../../utils/features";

const randomCode = () =>
  Array.from(crypto.getRandomValues(new Uint8Array(8)))
    .map((b) => "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[b % 32])
    .join("");

const Coupon = () => {
  const { data, isLoading } = useAllCouponsQuery();
  const [newCoupon, { isLoading: creating }] = useNewCouponMutation();
  const [deleteCoupon] = useDeleteCouponMutation();
  const [code, setCode] = useState("");
  const [amount, setAmount] = useState("");

  const columns: Column<CouponType>[] = [
    { Header: "Code", accessor: "coupon" },
    { Header: "Discount", accessor: "amount", width: 160, Cell: ({ value }: CellProps<CouponType>) => formatINR(value) } as Column<CouponType>,
    {
      Header: "",
      id: "delete",
      width: 70,
      disableSortBy: true,
      Cell: ({ row }: CellProps<CouponType>) => (
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
      ),
    } as Column<CouponType>,
  ];

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = await newCoupon({ coupon: code.trim(), amount: Number(amount) });
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    setCode("");
    setAmount("");
  };

  return (
    <AdminLayout>
      <DataTable
        columns={columns}
        data={data?.coupons ?? []}
        isLoading={isLoading}
        searchPlaceholder="Search coupons…"
        actions={
          <form className="inline-form" onSubmit={submit}>
            <input
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              placeholder="CODE"
              pattern="[A-Za-z0-9_\-]{3,32}"
              title="3-32 letters, digits, _ or -"
              required
              aria-label="Coupon code"
            />
            <button type="button" className="btn" onClick={() => setCode(randomCode())}>
              Generate
            </button>
            <input
              type="number"
              min={1}
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="₹ off"
              required
              aria-label="Discount amount"
            />
            <button type="submit" className="btn btn--primary" disabled={creating}>
              Create
            </button>
          </form>
        }
      />
    </AdminLayout>
  );
};

export default Coupon;
