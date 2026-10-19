import { Link, useParams } from "react-router-dom";
import Loader from "../components/loader";
import StatusBadge from "../components/admin/StatusBadge";
import { useOrderDetailsQuery } from "../redux/api/orderAPI";
import { formatDate, formatDateTime, formatINR, orderLabel, transformImage } from "../utils/features";

const OrderDetails = () => {
  const { id = "" } = useParams();
  const { data, isLoading, isError } = useOrderDetailsQuery(id);

  if (isLoading) return <Loader />;
  if (isError || !data) {
    return (
      <div className="container">
        <h1>Order not found</h1>
        <Link to="/orders">Back to my orders</Link>
      </div>
    );
  }

  const o = data.order;
  const s = o.shippingInfo;

  return (
    <div className="container order-details">
      <h1>
        Order {orderLabel(o)} <StatusBadge status={o.status} />
      </h1>
      <p>Placed on {formatDate(o.createdAt)}</p>
      <div className="detail-grid">
        <section className="card card--scroll">
          <h4>Items</h4>
          <ul className="order-items">
            {o.orderItems.map((i) => (
              <li key={`${i.productId}:${i.variantId ?? ""}`}>
                <img src={transformImage(i.photo)} alt={i.name} />
                <span>
                  {i.name}
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
          <h4>Shipping to</h4>
          {s && (
            <p>
              {s.name && <strong>{s.name} · {s.phone}<br /></strong>}
              {s.address}, {s.city}, {s.state}, {s.country} – {s.pinCode}
            </p>
          )}
          {o.billingInfo && (
            <>
              <h4>Billing address</h4>
              <p>{o.billingInfo.address}, {o.billingInfo.city}, {o.billingInfo.state}, {o.billingInfo.country} – {o.billingInfo.pinCode}</p>
            </>
          )}
          {o.notes && (
            <>
              <h4>Delivery notes</h4>
              <p>{o.notes}</p>
            </>
          )}
          <h4>Payment</h4>
          <dl className="facts">
            <dt>Subtotal</dt><dd>{formatINR(o.subtotal)}</dd>
            <dt>Shipping</dt><dd>{formatINR(o.shippingCharges)}</dd>
            <dt>Tax</dt><dd>{formatINR(o.tax)}</dd>
            <dt>Discount</dt><dd className="red">−{formatINR(o.discount)}</dd>
            <dt><strong>Total</strong></dt><dd><strong>{formatINR(o.total)}</strong></dd>
          </dl>
          {o.payment?.status && (
            <p>
              Payment {o.payment.status.replace(/_/g, " ").toLowerCase()}
              {o.payment.method ? ` by ${o.payment.method}` : ""}
              {o.payment.paidAt ? ` on ${formatDateTime(o.payment.paidAt)}` : ""}
              {o.couponCode ? ` · coupon ${o.couponCode}` : ""}
            </p>
          )}
          {(o.payment?.refunds ?? []).length > 0 && (
            <ul className="refund-list">
              {o.payment!.refunds!.map((r) => (
                <li key={`${r.at}-${r.amount}`}>
                  <strong>Refunded {formatINR(r.amount)}</strong> <small>{formatDateTime(r.at)}</small>
                  <span>{r.reason}</span>
                </li>
              ))}
            </ul>
          )}
          <p><Link to={`/order/${o._id}/invoice`}>View invoice</Link></p>
          {o.statusHistory.length > 0 && (
            <>
              <h4>Order timeline</h4>
              <ol className="order-timeline">
                {o.statusHistory.map((h) => (
                  <li key={`${h.status}-${h.at}`}>
                    <strong>{h.status}</strong> <small>{formatDateTime(h.at)}</small>
                  </li>
                ))}
              </ol>
            </>
          )}
        </section>
      </div>
    </div>
  );
};

export default OrderDetails;
