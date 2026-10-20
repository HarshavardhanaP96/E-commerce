import { useState } from "react";
import { LuTrendingDown, LuTrendingUp } from "react-icons/lu";
import { useNavigate } from "react-router-dom";
import AdminLayout from "../../components/admin/AdminLayout";
import { BarChart, DoughnutChart, LineChart } from "../../components/admin/Charts";
import { chartColors as c } from "../../components/admin/chartTheme";
import Loader from "../../components/admin/Loader";
import StatusBadge from "../../components/admin/StatusBadge";
import {
  useGetBarChartQuery,
  useGetDashboardStatsQuery,
  useGetLineChartQuery,
  useGetPieChartQuery,
} from "../../redux/api/dashboardAPI";
import { useCategoriesQuery, useLowStockQuery } from "../../redux/api/productAPI";
import { formatMoney, lastMonths, transformImage } from "../../utils/features";

const Widget = ({ label, value, change }: { label: string; value: string; change: number }) => (
  <article className="kpi">
    <span>{label}</span>
    <strong>{value}</strong>
    <small className={change >= 0 ? "green" : "red"}>
      {change >= 0 ? <LuTrendingUp /> : <LuTrendingDown />} {change > 0 ? "+" : ""}
      {change}% <em>vs last month</em>
    </small>
  </article>
);

const Analytics = () => {
  const navigate = useNavigate();
  const [category, setCategory] = useState("");
  const scope = { category };
  const { data: categories } = useCategoriesQuery();
  const stats = useGetDashboardStatsQuery(scope);
  const pie = useGetPieChartQuery(scope);
  const bar = useGetBarChartQuery(scope);
  const line = useGetLineChartQuery(scope);
  const { data: lowStock } = useLowStockQuery();
  // Not narrowed by the category filter: alerts are store-wide.
  const alerts = lowStock?.alerts ?? [];

  if (stats.isLoading || pie.isLoading || bar.isLoading || line.isLoading) return <Loader />;
  if (stats.isError || !stats.data || !pie.data || !bar.data || !line.data) {
    return (
      <AdminLayout>
        <p className="data-table__empty">Could not load analytics. Please retry.</p>
      </AdminLayout>
    );
  }

  const s = stats.data.stats;
  const p = pie.data.charts;
  const b = bar.data.charts;
  const l = line.data.charts;
  const six = lastMonths(6);
  const twelve = lastMonths(12);

  return (
    <AdminLayout>
      <div className="analytics">
        <header className="analytics__toolbar">
          <h2>Analytics</h2>
          <select value={category} onChange={(e) => setCategory(e.target.value)} aria-label="Scope by category">
            <option value="">All categories</option>
            {categories?.categories.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </header>

        <section className="kpi-row">
          <Widget label="Revenue" value={formatMoney(s.count.revenue)} change={s.changePercent.revenue} />
          <Widget label="Customers" value={String(s.count.user)} change={s.changePercent.user} />
          <Widget label="Products" value={String(s.count.product)} change={s.changePercent.product} />
          <Widget label="Orders" value={String(s.count.order)} change={s.changePercent.order} />
        </section>

        <section className="analytics__grid">
          <div className="card chart-card chart-card--wide low-stock">
            <h4>
              Low stock {alerts.length > 0 && <span className="badge badge--processing">{alerts.length}</span>}
            </h4>
            {alerts.length === 0 ? (
              <p className="low-stock__empty">Everything is above its low-stock level.</p>
            ) : (
              <ul>
                {alerts.slice(0, 20).map((a) => (
                  <li key={`${a.productId}:${a.variantId ?? ""}`}>
                    <button type="button" onClick={() => navigate(`/admin/product/${a.productId}`)}>
                      <img src={transformImage(a.photo)} alt="" />
                      <span>
                        {a.name}
                        {a.label && <small> · {a.label}</small>}
                        {a.sku && <small className="low-stock__sku">{a.sku}</small>}
                      </span>
                      <b className={a.out ? "red" : ""}>{a.out ? "Out" : `${a.stock} left`}</b>
                    </button>
                  </li>
                ))}
                {alerts.length > 20 && <li className="low-stock__more">…and {alerts.length - 20} more</li>}
              </ul>
            )}
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>Revenue & orders (6 months)</h4>
            <BarChart data_1={s.chart.revenue} data_2={s.chart.order} title_1="Revenue" title_2="Orders" dualAxis
                      bgColor_1={c.series[0]} bgColor_2={c.series[1]} labels={six} />
          </div>
          <div className="card chart-card">
            <h4>Inventory by category</h4>
            <ul className="category-bars">
              {s.categoryCount.map((c) => (
                <li key={c.category}>
                  <span>{c.category}</span>
                  <div><i style={{ width: `${c.percent}%` }} /></div>
                  <b>{c.percent}%</b>
                </li>
              ))}
              {s.categoryCount.length === 0 && <li>No products yet</li>}
            </ul>
          </div>
          <div className="card chart-card">
            <h4>Gender ratio</h4>
            <DoughnutChart labels={["Female", "Male"]} data={[s.genderRatio.female, s.genderRatio.male]}
                           backgroundColor={[c.series[4], c.series[1]]} cutout="72%" />
          </div>
          <div className="card chart-card">
            <h4>Order fulfilment</h4>
            <DoughnutChart labels={["Processing", "Shipped", "Delivered"]}
                           data={[p.orderFulfillment.processing, p.orderFulfillment.shipped, p.orderFulfillment.delivered]}
                           backgroundColor={[c.warning, c.info, c.success]} cutout="72%" />
          </div>
          <div className="card chart-card">
            <h4>Stock availability</h4>
            <DoughnutChart labels={["In stock", "Out of stock"]}
                           data={[p.stockAvailability.inStock, p.stockAvailability.outOfStock]}
                           backgroundColor={[c.success, c.danger]} cutout="72%" />
          </div>
          <div className="card chart-card">
            <h4>Revenue distribution</h4>
            <DoughnutChart labels={["Tax", "Shipping", "Discount", "Net"]}
                           data={[p.revenueDistribution.totalTax, p.revenueDistribution.totalShippingCharges,
                                  p.revenueDistribution.totalDiscount, p.revenueDistribution.totalIncome]}
                           backgroundColor={[c.series[0], c.series[3], c.danger, c.success]} cutout="72%" />
          </div>
          <div className="card chart-card">
            <h4>Customers by age</h4>
            <DoughnutChart labels={["Under 20", "20–40", "Over 40"]}
                           data={[p.ageGroup.teen, p.ageGroup.adult, p.ageGroup.old]}
                           backgroundColor={[c.series[1], c.series[0], c.series[4]]} cutout="72%" />
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>New products & users (6 months)</h4>
            <BarChart data_1={b.products} data_2={b.users} title_1="Products" title_2="Users"
                      bgColor_1={c.series[0]} bgColor_2={c.series[1]} labels={six} />
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>Revenue (12 months)</h4>
            <LineChart data={l.revenue} label="Revenue" labels={twelve}
                       backgroundColor={c.fill(c.accent)} borderColor={c.accent} />
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>Discount given (12 months)</h4>
            <LineChart data={l.discount} label="Discount" labels={twelve}
                       backgroundColor={c.fill(c.series[3])} borderColor={c.series[3]} />
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>Latest transactions</h4>
            <table className="mini-table">
              <thead>
                <tr><th>Order</th><th>Items</th><th>Discount</th><th>Amount</th><th>Status</th></tr>
              </thead>
              <tbody>
                {s.latestTransaction.map((t) => (
                  <tr key={t.id} className="clickable" onClick={() => navigate(`/admin/transaction/${t.id}`)}>
                    <td>{t.id.slice(-8).toUpperCase()}</td>
                    <td>{t.quantity}</td>
                    <td>{formatMoney(t.discount)}</td>
                    <td>{formatMoney(t.amount)}</td>
                    <td><StatusBadge status={t.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </AdminLayout>
  );
};

export default Analytics;
