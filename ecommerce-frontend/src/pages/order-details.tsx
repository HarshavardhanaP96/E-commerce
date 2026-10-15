import { Link, useParams } from "react-router-dom";
import Loader from "../components/loader";
import StatusBadge from "../components/admin/StatusBadge";
import { useOrderDetailsQuery } from "../redux/api/orderAPI";
import { formatDate, formatINR, transformImage } from "../utils/features";

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
        Order {o._id.slice(-8).toUpperCase()} <StatusBadge status={o.status} />
      </h1>
      <p>Placed on {formatDate(o.createdAt)}</p>
      <div className="detail-grid">
        <section className="card card--scroll">
          <h4>Items</h4>
          <ul className="order-items">
            {o.orderItems.map((i) => (
              <li key={i.productId}>
                <img src={transformImage(i.photo)} alt={i.name} />
                <span>{i.name}</span>
                <span>
                  {formatINR(i.price)} × {i.quantity} = <strong>{formatINR(i.price * i.quantity)}</strong>
                </span>
              </li>
            ))}
          </ul>
        </section>
        <section className="card">
          <h4>Shipping to</h4>
          {s && <p>{s.address}, {s.city}, {s.state}, {s.country} – {s.pinCode}</p>}
          <h4>Payment</h4>
          <dl className="facts">
            <dt>Subtotal</dt><dd>{formatINR(o.subtotal)}</dd>
            <dt>Shipping</dt><dd>{formatINR(o.shippingCharges)}</dd>
            <dt>Tax</dt><dd>{formatINR(o.tax)}</dd>
            <dt>Discount</dt><dd className="red">−{formatINR(o.discount)}</dd>
            <dt><strong>Total</strong></dt><dd><strong>{formatINR(o.total)}</strong></dd>
          </dl>
        </section>
      </div>
    </div>
  );
};

export default OrderDetails;
