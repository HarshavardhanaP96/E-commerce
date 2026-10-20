import { Link } from "react-router-dom";
import { useGetOrdersSectionQuery } from "../../../redux/api/dashboardAPI";
import { SectionParams } from "../../../types/analytics";
import { formatDate, formatMoney } from "../../../utils/features";
import { BarChart, DoughnutChart, LineChart } from "../Charts";
import { chartColors as c } from "../chartTheme";
import { alignPrevious, bucketLabel, formatHours } from "./sections";
import { CsvButton, Kpi, SectionState } from "./Shared";

const OrdersTab = ({ params }: { params: SectionParams }) => {
  const { data, isLoading, isError } = useGetOrdersSectionQuery(params);
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const f = s.fulfilment;
  const labels = s.series.map((p) => bucketLabel(p.start, s.granularity));

  return (
    <>
      <div className="analytics__actions"><CsvButton section="orders" params={params} /></div>
      {s.truncated && <p className="analytics__note">This range has more than 50,000 orders; figures cover the newest 50,000.</p>}
      <section className="kpi-row">
        <Kpi label="Orders" value={String(s.series.reduce((n, p) => n + p.orders, 0))} />
        <Kpi label="Median time to deliver" value={formatHours(f.placedToDelivered.medianHours)} />
        <Kpi label={`Stuck over ${s.stuckAfterDays} days`} value={String(s.stuckCount)} />
        {s.refunds && <Kpi label="Refund rate" value={`${s.refunds.rate}%`} />}
      </section>
      <section className="analytics__grid orders-grid">
        <div className="card chart-card chart-card--wide">
          <h4>Orders by {s.granularity}</h4>
          <BarChart data_1={s.series.map((p) => p.orders)} data_2={alignPrevious(s.series, s.previousSeries, (p) => p.orders)}
                    title_1="This period" title_2={s.previousSeries ? "Comparison period" : ""}
                    bgColor_1={c.series[1]} bgColor_2={c.series[2]} labels={labels} />
        </div>
        <div className="card chart-card">
          <h4>Status of orders placed</h4>
          <DoughnutChart labels={["Processing", "Shipped", "Delivered"]}
                         data={[s.byStatus.processing ?? 0, s.byStatus.shipped ?? 0, s.byStatus.delivered ?? 0]}
                         backgroundColor={[c.warning, c.info, c.success]} cutout="72%" />
        </div>
        <div className="card chart-card chart-card--wide orders-table">
          <h4>How long orders take</h4>
          <table className="mini-table">
            <thead><tr><th>Step</th><th>Median</th><th>Slowest 10%</th><th>Orders</th></tr></thead>
            <tbody>
              {([["Placed → shipped", f.placedToShipped], ["Shipped → delivered", f.shippedToDelivered],
                 ["Placed → delivered", f.placedToDelivered]] as const).map(([label, leg]) => (
                <tr key={label}><td>{label}</td><td>{formatHours(leg.medianHours)}</td>
                  <td>{formatHours(leg.p90Hours)}</td><td>{leg.count}</td></tr>
              ))}
            </tbody>
          </table>
          {f.withoutHistory > 0 && <p className="analytics__note">{f.withoutHistory} older orders have no status history and are left out.</p>}
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Median hours from order to delivery</h4>
          <LineChart data={f.deliveryTrend.map((p) => p.medianHours ?? 0)} label="Hours"
                     labels={f.deliveryTrend.map((p) => bucketLabel(p.start, s.granularity))}
                     backgroundColor={c.fill(c.accent)} borderColor={c.accent} />
        </div>
        <div className="card chart-card chart-card--wide orders-table">
          <h4>Stuck orders (processing for more than {s.stuckAfterDays} days)</h4>
          {s.stuck.length === 0 ? <p className="data-table__empty">Nothing is stuck.</p> : (
            <table className="mini-table">
              <thead><tr><th>Order</th><th>Placed</th><th>Days waiting</th><th>Total</th></tr></thead>
              <tbody>
                {s.stuck.map((o) => (
                  <tr key={o.id}>
                    <td><Link to={`/admin/transaction/${o.id}`}>{o.orderNumber ?? o.id.slice(-8).toUpperCase()}</Link></td>
                    <td>{formatDate(o.placedAt)}</td><td>{o.ageDays}</td><td>{formatMoney(o.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {s.stuckCount > s.stuck.length && <p className="analytics__note">…and {s.stuckCount - s.stuck.length} more.</p>}
        </div>
        <div className="card chart-card">
          <h4>Items per order (average {s.size.averageItems})</h4>
          <ul className="category-bars">
            {Object.entries(s.size.bands).map(([band, n]) => {
              const total = Object.values(s.size.bands).reduce((a, b) => a + b, 0);
              return (
                <li key={band}><span>{band}</span><div><i style={{ width: `${total ? (n / total) * 100 : 0}%` }} /></div><b>{n}</b></li>
              );
            })}
          </ul>
        </div>
        <div className="card chart-card orders-table">
          <h4>Most used coupons</h4>
          {s.size.topCoupons.length === 0 ? <p className="data-table__empty">No coupons used.</p> : (
            <table className="mini-table">
              <thead><tr><th>Code</th><th>Orders</th><th>Discount</th></tr></thead>
              <tbody>{s.size.topCoupons.map((cp) => (
                <tr key={cp.code}><td>{cp.code}</td><td>{cp.orders}</td><td>{formatMoney(cp.discount)}</td></tr>
              ))}</tbody>
            </table>
          )}
        </div>
      </section>
    </>
  );
};

export default OrdersTab;
