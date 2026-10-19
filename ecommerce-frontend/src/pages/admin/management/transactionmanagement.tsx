import { useEffect } from "react";
import toast from "react-hot-toast";
import { LuFileText, LuTrash2 } from "react-icons/lu";
import { Link, useNavigate, useParams } from "react-router-dom";
import DetailLayout from "../../../components/admin/DetailLayout";
import Loader from "../../../components/admin/Loader";
import PaymentPanel from "../../../components/admin/PaymentPanel";
import StatusBadge from "../../../components/admin/StatusBadge";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useDeleteOrderMutation, useOrderDetailsQuery, useProcessOrderMutation } from "../../../redux/api/orderAPI";
import { formatDate, formatDateTime, formatINR, orderLabel, transformImage } from "../../../utils/features";

const NEXT_ACTION: Record<string, string> = { Processing: "Mark shipped", Shipped: "Mark delivered" };

const TransactionManagement = () => {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const { data, isLoading, isError } = useOrderDetailsQuery(id);
  const [processOrder, { isLoading: saving }] = useProcessOrderMutation();
  const [deleteOrder, { isLoading: deleting }] = useDeleteOrderMutation();

  useEffect(() => {
    if (isError) {
      toast.error("Order not found");
      navigate("/admin/transaction", { replace: true });
    }
  }, [isError, navigate]);

  const order = data?.order;
  if (isLoading || !order) return <Loader />;

  const advance = async () => {
    const res = await processOrder(id);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
  };

  const remove = async () => {
    if (!confirm("Delete this order? This cannot be undone.")) return;
    const res = await deleteOrder(id);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    navigate("/admin/transaction");
  };

  const { shippingInfo: s } = order;

  return (
    <DetailLayout
      title={`Order ${orderLabel(order)}`}
      subtitle={<>Placed {formatDate(order.createdAt)} · <StatusBadge status={order.status} /></>}
      actions={
        <>
          {NEXT_ACTION[order.status] && (
            <button className="btn btn--primary" onClick={advance} disabled={saving}>
              {saving ? "Updating…" : NEXT_ACTION[order.status]}
            </button>
          )}
          <Link className="btn" to={`/order/${id}/invoice`}>
            <LuFileText /> Invoice
          </Link>
          <button className="btn btn--danger" onClick={remove} disabled={deleting}>
            <LuTrash2 /> Delete
          </button>
        </>
      }
    >
      <div className="detail-grid">
        <section className="card card--scroll">
          <h4>Items</h4>
          <ul className="order-items">
            {order.orderItems.map((i) => (
              <li key={`${i.productId}:${i.variantId ?? ""}`}>
                <img src={transformImage(i.photo)} alt={i.name} />
                <span>
                  <Link to={`/admin/product/${i.productId}`}>{i.name}</Link>
                  {i.variantLabel && <small> · {i.variantLabel}</small>}
                  {i.sku && <small> · SKU {i.sku}</small>}
                </span>
                <span>
                  {formatINR(i.price)} × {i.quantity} = <strong>{formatINR(i.price * i.quantity)}</strong>
                </span>
              </li>
            ))}
          </ul>
        </section>

        <section className="card">
          <h4>Customer</h4>
          <p>
            <Link to={`/admin/customer/${order.user._id}`}>{order.user.name}</Link>
          </p>
          {order.customer && (
            <p>
              {order.customer.email}
              {order.customer.phone ? ` · ${order.customer.phone}` : ""}
            </p>
          )}
          {s && (
            <p>
              {s.name && <strong>{s.name}{s.phone ? ` · ${s.phone}` : ""}<br /></strong>}
              {s.address}, {s.city}, {s.state}, {s.country} – {s.pinCode}
            </p>
          )}
          {order.billingInfo && (
            <>
              <h4>Billing address</h4>
              <p>
                {order.billingInfo.address}, {order.billingInfo.city}, {order.billingInfo.state},{" "}
                {order.billingInfo.country} – {order.billingInfo.pinCode}
              </p>
            </>
          )}
          {order.notes && (
            <>
              <h4>Delivery notes</h4>
              <p>{order.notes}</p>
            </>
          )}
          {order.statusHistory.length > 0 && (
            <>
              <h4>Order timeline</h4>
              <ol className="order-timeline">
                {order.statusHistory.map((h) => (
                  <li key={`${h.status}-${h.at}`}>
                    <strong>{h.status}</strong> <small>{formatDateTime(h.at)}</small>
                  </li>
                ))}
              </ol>
            </>
          )}
          {order.payment && <PaymentPanel orderId={id} payment={order.payment} />}
          <h4>Amount</h4>
          <dl className="facts">
            <dt>Subtotal</dt><dd>{formatINR(order.subtotal)}</dd>
            <dt>Shipping</dt><dd>{formatINR(order.shippingCharges)}</dd>
            <dt>Tax</dt><dd>{formatINR(order.tax)}</dd>
            <dt>Discount</dt><dd className="red">−{formatINR(order.discount)}</dd>
            <dt><strong>Total</strong></dt><dd><strong>{formatINR(order.total)}</strong></dd>
          </dl>
        </section>
      </div>
    </DetailLayout>
  );
};

export default TransactionManagement;
