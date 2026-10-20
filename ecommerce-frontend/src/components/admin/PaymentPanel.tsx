import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { errorMessage } from "../../redux/api/baseQuery";
import { useRefundOrderMutation } from "../../redux/api/orderAPI";
import { OrderPayment } from "../../types/types";
import { formatDateTime, formatMoney } from "../../utils/features";
import StatusBadge from "./StatusBadge";

/** How the order was paid, what has been returned, and a form to return more. Admin only. */
const PaymentPanel = ({ orderId, payment }: { orderId: string; payment: OrderPayment }) => {
  const [refundOrder, { isLoading }] = useRefundOrderMutation();
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");

  const total = payment.amount ?? 0;
  const refunded = payment.refunded ?? 0;
  const left = Math.max(0, Math.round((total - refunded) * 100) / 100);
  const canRefund = !!payment.status && payment.status !== "REFUNDED" && left > 0;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const wanted = amount === "" ? left : Number(amount);
    if (!confirm(`Refund ${formatMoney(wanted)} to the customer's original payment method? This cannot be undone.`)) return;
    const res = await refundOrder({ id: orderId, amount: amount === "" ? undefined : Number(amount), reason: reason.trim() });
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    setAmount("");
    setReason("");
  };

  return (
    <>
      <h4>Payment</h4>
      <dl className="facts">
        <dt>Status</dt><dd>{payment.status ? <StatusBadge status={payment.status} /> : "—"}</dd>
        <dt>Method</dt><dd>{payment.method ?? "Unknown"}</dd>
        <dt>Provider</dt><dd>{payment.provider}</dd>
        <dt>Paid</dt><dd>{formatMoney(total)}{payment.paidAt ? ` on ${formatDateTime(payment.paidAt)}` : ""}</dd>
        <dt>Refunded</dt><dd>{formatMoney(refunded)}</dd>
      </dl>

      {payment.failureReason && <p className="red" role="alert">{payment.failureReason}</p>}

      {(payment.refunds ?? []).length > 0 && (
        <ul className="refund-list">
          {payment.refunds!.map((r) => (
            <li key={`${r.at}-${r.amount}`}>
              <strong>{formatMoney(r.amount)}</strong> <small>{formatDateTime(r.at)} · {r.status}</small>
              <span>{r.reason}</span>
            </li>
          ))}
        </ul>
      )}

      {canRefund && (
        <form className="refund-form" onSubmit={submit}>
          <label>
            Amount (₹), up to {formatMoney(left)}
            <input type="number" min={0.01} max={left} step="0.01" value={amount}
                   placeholder={`Leave empty to refund ${formatMoney(left)}`} onChange={(e) => setAmount(e.target.value)} />
          </label>
          <label>
            Reason (the customer can see this)
            <input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={200} required />
          </label>
          <button type="submit" className="btn" disabled={isLoading || !reason.trim()}>
            {isLoading ? "Refunding…" : "Issue refund"}
          </button>
        </form>
      )}
    </>
  );
};

export default PaymentPanel;
