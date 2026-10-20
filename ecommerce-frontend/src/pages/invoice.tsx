import { LuPrinter } from "react-icons/lu";
import { Link, useParams } from "react-router-dom";
import Loader from "../components/loader";
import { useOrderInvoiceQuery } from "../redux/api/orderAPI";
import { ShippingInfo } from "../types/types";
import { formatDate, formatMoney } from "../utils/features";

const address = (a?: ShippingInfo | null) =>
  a ? [a.address, a.city, a.state, a.country].filter(Boolean).join(", ") + (a.pinCode ? ` – ${a.pinCode}` : "") : "—";

const pct = (n: number) => `${n}%`;

/** A printable invoice built from the order. "Print" lets the browser save it as a PDF. */
const InvoicePage = () => {
  const { id = "" } = useParams();
  const { data, isLoading, isError } = useOrderInvoiceQuery(id);

  if (isLoading) return <Loader />;
  if (isError || !data) {
    return (
      <div className="container">
        <h1>Invoice not found</h1>
        <Link to="/orders">Back to my orders</Link>
      </div>
    );
  }

  const inv = data.invoice;
  const intraState = inv.taxSummary.some((t) => t.cgst > 0 || t.sgst > 0);

  return (
    <div className="container invoice-page">
      <div className="invoice__toolbar">
        <Link to={`/order/${id}`}>← Back to order</Link>
        <button className="btn btn--primary" onClick={() => window.print()}>
          <LuPrinter /> Print / Save as PDF
        </button>
      </div>

      <article className="invoice" aria-label={`Invoice ${inv.number}`}>
        <header className="invoice__head">
          <div>
            <h1>Invoice</h1>
            <p><strong>{inv.number}</strong></p>
            <p>Date: {formatDate(inv.date)}</p>
            {inv.orderNumber && <p>Order: {inv.orderNumber}</p>}
          </div>
          <div className="invoice__seller">
            <strong>{inv.seller.name}</strong>
            {inv.seller.address && <span>{inv.seller.address}</span>}
            {inv.seller.gstin && <span>GSTIN: {inv.seller.gstin}</span>}
            {inv.seller.email && <span>{inv.seller.email}</span>}
            {inv.seller.phone && <span>{inv.seller.phone}</span>}
          </div>
        </header>

        <section className="invoice__parties">
          <div>
            <h4>Billed to</h4>
            {inv.buyer?.name && <p><strong>{inv.buyer.name}</strong></p>}
            <p>{address(inv.billTo)}</p>
            {inv.buyer?.email && <p>{inv.buyer.email}</p>}
          </div>
          <div>
            <h4>Shipped to</h4>
            {inv.shipTo?.name && <p><strong>{inv.shipTo.name}</strong></p>}
            <p>{address(inv.shipTo)}</p>
          </div>
        </section>

        <table className="invoice__lines">
          <thead>
            <tr><th>Item</th><th>Qty</th><th>Rate</th><th>Tax</th><th>Amount</th></tr>
          </thead>
          <tbody>
            {inv.lines.map((l, i) => (
              <tr key={`${l.description}-${i}`}>
                <td>{l.description}{l.sku && <small> · SKU {l.sku}</small>}</td>
                <td>{l.quantity}</td>
                <td>{formatMoney(l.unitPrice)}</td>
                <td>{pct(l.taxRate)}</td>
                <td>{formatMoney(l.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div className="invoice__bottom">
          <table className="invoice__tax">
            <caption>Tax summary</caption>
            <thead>
              <tr>
                <th>Rate</th><th>Taxable</th>
                {intraState ? <><th>CGST</th><th>SGST</th></> : <th>IGST</th>}
              </tr>
            </thead>
            <tbody>
              {inv.taxSummary.map((t) => (
                <tr key={t.rate}>
                  <td>{pct(t.rate)}</td>
                  <td>{formatMoney(t.taxableValue)}</td>
                  {intraState ? <><td>{formatMoney(t.cgst)}</td><td>{formatMoney(t.sgst)}</td></> : <td>{formatMoney(t.igst)}</td>}
                </tr>
              ))}
            </tbody>
          </table>

          <dl className="facts invoice__totals">
            <dt>Subtotal</dt><dd>{formatMoney(inv.subtotal)}</dd>
            {inv.discount > 0 && <><dt>Discount</dt><dd>−{formatMoney(inv.discount)}</dd></>}
            <dt>Shipping</dt><dd>{formatMoney(inv.shippingCharges)}</dd>
            <dt>Tax</dt><dd>{formatMoney(inv.tax)}</dd>
            <dt><strong>Total</strong></dt><dd><strong>{formatMoney(inv.total)}</strong></dd>
            {inv.refunded > 0 && <><dt>Refunded</dt><dd>−{formatMoney(inv.refunded)}</dd></>}
          </dl>
        </div>

        <footer className="invoice__foot">
          {inv.paymentStatus && (
            <p>
              Payment: {inv.paymentStatus.replace(/_/g, " ").toLowerCase()}
              {inv.paymentMethod ? ` by ${inv.paymentMethod}` : ""}
            </p>
          )}
          <p>This is a computer-generated invoice.</p>
        </footer>
      </article>
    </div>
  );
};

export default InvoicePage;
