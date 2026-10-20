import { ReactNode, useState } from "react";
import toast from "react-hot-toast";
import { LuDownload } from "react-icons/lu";
import { useNavigate } from "react-router-dom";
import {
  useGetCouponsSectionQuery,
  useGetCustomersSectionQuery,
  useGetPaymentsSectionQuery,
  useGetProductsSectionQuery,
  useGetTaxSectionQuery,
} from "../../redux/api/dashboardAPI";
import { bucketLabel, DateRange } from "../../utils/dateRange";
import { formatMoney } from "../../utils/features";
import { daysLeftLabel, downloadCsv, ExportTable, SectionTab } from "../../utils/sections";
import { BarChart, DoughnutChart } from "./Charts";
import { chartColors as c } from "./chartTheme";

/** One card with a title, an optional CSV download for its table, and the table or chart. */
const Card = ({ title, table, range, wide, children }: {
  title: string; table?: ExportTable; range: DateRange; wide?: boolean; children: ReactNode;
}) => {
  const [busy, setBusy] = useState(false);
  const download = async () => {
    if (!table) return;
    setBusy(true);
    try {
      await downloadCsv(table, range);
    } catch {
      toast.error("Could not download the CSV");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className={`card chart-card section-card${wide ? " chart-card--wide" : ""}`}>
      <header className="section-card__head">
        <h4>{title}</h4>
        {table && (
          <button type="button" className="btn btn--sm" onClick={download} disabled={busy}
                  aria-label={`Download ${title} as CSV`}>
            <LuDownload aria-hidden /> CSV
          </button>
        )}
      </header>
      {children}
    </div>
  );
};

const Empty = ({ children }: { children: ReactNode }) => <p className="section-card__empty">{children}</p>;

const Kpi = ({ label, value }: { label: string; value: string }) => (
  <article className="card kpi">
    <p>{label}</p>
    <h4>{value}</h4>
  </article>
);

const Status = ({ loading, error }: { loading: boolean; error: boolean }) =>
  loading ? <p className="section-card__empty">Loading…</p>
    : error ? <p className="data-table__empty">Could not load this section. Please retry.</p> : null;

const Products = ({ range }: { range: DateRange }) => {
  const navigate = useNavigate();
  const { data, isLoading, isError } = useGetProductsSectionQuery(range);
  if (!data) return <Status loading={isLoading} error={isError} />;
  const p = data.products;
  return (
    <section className="analytics__grid">
      <Card title="Top sellers" table="top-products" range={range} wide>
        {p.top.length === 0 ? <Empty>No sales in this range.</Empty> : (
          <table className="mini-table">
            <thead><tr><th>Product</th><th>Units</th><th>Orders</th><th>Revenue</th></tr></thead>
            <tbody>
              {p.top.map((r) => (
                <tr key={r.productId} className="clickable" onClick={() => navigate(`/admin/product/${r.productId}`)}>
                  <td>{r.name}</td><td>{r.units}</td><td>{r.orders}</td><td>{formatMoney(r.revenue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      <Card title="Revenue by category" table="categories" range={range}>
        {p.categories.length === 0 ? <Empty>No sales in this range.</Empty> : (
          <ul className="category-bars">
            {p.categories.map((r) => (
              <li key={r.category}>
                <span>{r.category}</span>
                <div><i style={{ width: `${r.share}%` }} /></div>
                <b>{r.share}%</b>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Not sold in this range" table="idle-products" range={range}>
        {p.idle.length === 0 ? <Empty>Every product on sale sold at least once.</Empty> : (
          <table className="mini-table">
            <thead><tr><th>Product</th><th>Stock</th></tr></thead>
            <tbody>
              {p.idle.slice(0, 20).map((r) => (
                <tr key={r.productId} className="clickable" onClick={() => navigate(`/admin/product/${r.productId}`)}>
                  <td>{r.name}</td><td>{r.stock}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      <Card title="Low stock: days left at this range's selling rate" table="stock" range={range} wide>
        {p.stock.length === 0 ? <Empty>Everything is above its low-stock level.</Empty> : (
          <table className="mini-table">
            <thead><tr><th>Product</th><th>Stock</th><th>Sold in range</th><th>Days left</th></tr></thead>
            <tbody>
              {p.stock.map((r) => (
                <tr key={`${r.productId}:${r.variantId ?? ""}`} className="clickable"
                    onClick={() => navigate(`/admin/product/${r.productId}`)}>
                  <td>{r.name}{r.label && <small> · {r.label}</small>}</td>
                  <td>{r.stock}</td><td>{r.unitsSold}</td>
                  <td className={r.daysLeft === 0 ? "red" : undefined}>{daysLeftLabel(r.daysLeft)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </section>
  );
};

const Customers = ({ range }: { range: DateRange }) => {
  const { data, isLoading, isError } = useGetCustomersSectionQuery(range);
  if (!data) return <Status loading={isLoading} error={isError} />;
  const s = data.customers;
  return (
    <>
      <section className="kpi-row">
        <Kpi label="Customers who ordered" value={String(s.customers)} />
        <Kpi label="New" value={String(s.newCustomers)} />
        <Kpi label="Returning" value={String(s.returningCustomers)} />
        <Kpi label="Ordered twice or more" value={`${s.repeatRate}%`} />
      </section>
      <section className="analytics__grid">
        <Card title="Top customers" table="top-customers" range={range} wide>
          {s.top.length === 0 ? <Empty>No orders in this range.</Empty> : (
            <table className="mini-table">
              <thead><tr><th>Customer</th><th>Email</th><th>Orders</th><th>Revenue</th></tr></thead>
              <tbody>
                {s.top.map((r) => (
                  <tr key={r.userId}>
                    <td>{r.name ?? "Erased account"}</td><td>{r.email ?? "—"}</td>
                    <td>{r.orders}</td><td>{formatMoney(r.revenue)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
        <Card title="Orders by place" table="places" range={range} wide>
          {s.places.length === 0 ? <Empty>No orders in this range.</Empty> : (
            <table className="mini-table">
              <thead><tr><th>City</th><th>State</th><th>Orders</th><th>Revenue</th></tr></thead>
              <tbody>
                {s.places.map((r) => (
                  <tr key={`${r.state}|${r.city}`}>
                    <td>{r.city ?? "—"}</td><td>{r.state ?? "—"}</td><td>{r.orders}</td><td>{formatMoney(r.revenue)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </section>
    </>
  );
};

const Coupons = ({ range }: { range: DateRange }) => {
  const { data, isLoading, isError } = useGetCouponsSectionQuery(range);
  if (!data) return <Status loading={isLoading} error={isError} />;
  const s = data.coupons;
  return (
    <>
      <section className="kpi-row">
        <Kpi label="Orders with a coupon" value={String(s.ordersWithCoupon)} />
        <Kpi label="Share of orders" value={`${s.shareOfOrders}%`} />
        <Kpi label="Discount given" value={formatMoney(s.discount)} />
      </section>
      <section className="analytics__grid">
        <Card title="Coupon codes" table="coupons" range={range} wide>
          {s.codes.length === 0 ? <Empty>No coupons used in this range.</Empty> : (
            <table className="mini-table">
              <thead><tr><th>Code</th><th>Uses</th><th>Discount cost</th><th>Revenue from these orders</th></tr></thead>
              <tbody>
                {s.codes.map((r) => (
                  <tr key={r.code}>
                    <td>{r.code}</td><td>{r.uses}</td><td>{formatMoney(r.discount)}</td><td>{formatMoney(r.revenue)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </section>
    </>
  );
};

const Payments = ({ range }: { range: DateRange }) => {
  const { data, isLoading, isError } = useGetPaymentsSectionQuery(range);
  if (!data) return <Status loading={isLoading} error={isError} />;
  const s = data.payments;
  const palette = [c.series[0], c.series[1], c.series[3], c.series[4], c.info, c.warning];
  return (
    <section className="analytics__grid">
      <Card title="Payment methods" table="payment-methods" range={range}>
        {s.methods.length === 0 ? <Empty>No orders in this range.</Empty> : (
          <DoughnutChart labels={s.methods.map((m) => `${m.method} (${m.share}%)`)} data={s.methods.map((m) => m.orders)}
                         backgroundColor={s.methods.map((_, i) => palette[i % palette.length])} cutout="72%" />
        )}
      </Card>
      <Card title={`Refunds by ${s.granularity}`} table="refunds" range={range} wide>
        <BarChart data_1={s.refunds.map((r) => r.amount)} data_2={s.refunds.map((r) => r.count)}
                  title_1="Amount refunded" title_2="Refunds" dualAxis bgColor_1={c.danger} bgColor_2={c.series[1]}
                  labels={s.refunds.map((r) => bucketLabel(r.start, s.granularity))} />
      </Card>
    </section>
  );
};

const Tax = ({ range }: { range: DateRange }) => {
  const { data, isLoading, isError } = useGetTaxSectionQuery(range);
  if (!data) return <Status loading={isLoading} error={isError} />;
  const s = data.tax;
  return (
    <>
      <section className="kpi-row">
        <Kpi label="Taxable value" value={formatMoney(s.totalTaxable)} />
        <Kpi label="GST collected" value={formatMoney(s.totalTax)} />
      </section>
      <section className="analytics__grid">
        <Card title="GST by month and rate" table="tax" range={range} wide>
          {!s.sellerState && (
            <p className="section-card__note">
              Set your state of registration under Store settings: until then all tax is shown as IGST.
            </p>
          )}
          {s.rows.length === 0 ? <Empty>No orders in this range.</Empty> : (
            <table className="mini-table">
              <thead>
                <tr><th>Month</th><th>Rate</th><th>Taxable value</th><th>CGST</th><th>SGST</th><th>IGST</th><th>Total</th></tr>
              </thead>
              <tbody>
                {s.rows.map((r) => (
                  <tr key={`${r.month}|${r.rate}`}>
                    <td>{r.month}</td><td>{r.rate}%</td><td>{formatMoney(r.taxableValue)}</td>
                    <td>{formatMoney(r.cgst)}</td><td>{formatMoney(r.sgst)}</td><td>{formatMoney(r.igst)}</td>
                    <td>{formatMoney(r.tax)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="section-card__note">
            On orders placed in the range, as charged at checkout. Refunds are not deducted: check credit notes
            with your accountant before filing.
          </p>
        </Card>
      </section>
    </>
  );
};

/** The section shown under the Analytics toolbar for every tab but Overview. */
const AnalyticsSection = ({ tab, range }: { tab: Exclude<SectionTab, "overview">; range: DateRange }) => {
  switch (tab) {
    case "products": return <Products range={range} />;
    case "customers": return <Customers range={range} />;
    case "coupons": return <Coupons range={range} />;
    case "payments": return <Payments range={range} />;
    case "tax": return <Tax range={range} />;
  }
};

export default AnalyticsSection;
