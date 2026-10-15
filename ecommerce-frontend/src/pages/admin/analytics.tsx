import { useState } from "react";
import { HiTrendingDown, HiTrendingUp } from "react-icons/hi";
import { useNavigate } from "react-router-dom";
import AdminLayout from "../../components/admin/AdminLayout";
import { BarChart, DoughnutChart, LineChart } from "../../components/admin/Charts";
import Loader from "../../components/admin/Loader";
import StatusBadge from "../../components/admin/StatusBadge";
import {
  useGetBarChartQuery,
  useGetDashboardStatsQuery,
  useGetLineChartQuery,
  useGetPieChartQuery,
} from "../../redux/api/dashboardAPI";
import { useCategoriesQuery } from "../../redux/api/productAPI";
import { formatINR, lastMonths } from "../../utils/features";

const Widget = ({ label, value, change }: { label: string; value: string; change: number }) => (
  <article className="kpi">
    <span>{label}</span>
    <strong>{value}</strong>
    <small className={change >= 0 ? "green" : "red"}>
      {change >= 0 ? <HiTrendingUp /> : <HiTrendingDown />} {change > 0 ? "+" : ""}
      {change}% vs last month
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
          <Widget label="Revenue" value={formatINR(s.count.revenue)} change={s.changePercent.revenue} />
          <Widget label="Customers" value={String(s.count.user)} change={s.changePercent.user} />
          <Widget label="Products" value={String(s.count.product)} change={s.changePercent.product} />
          <Widget label="Orders" value={String(s.count.order)} change={s.changePercent.order} />
        </section>

        <section className="analytics__grid">
          <div className="card chart-card chart-card--wide">
            <h4>Revenue & orders (6 months)</h4>
            <BarChart data_1={s.chart.revenue} data_2={s.chart.order} title_1="Revenue" title_2="Orders"
                      bgColor_1="rgb(99,102,241)" bgColor_2="rgba(16,185,129,0.8)" labels={six} />
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
                           backgroundColor={["hsl(340,82%,56%)", "rgba(53,162,235,0.8)"]} cutout={70} />
          </div>
          <div className="card chart-card">
            <h4>Order fulfilment</h4>
            <DoughnutChart labels={["Processing", "Shipped", "Delivered"]}
                           data={[p.orderFulfillment.processing, p.orderFulfillment.shipped, p.orderFulfillment.delivered]}
                           backgroundColor={["#f59e0b", "#6366f1", "#10b981"]} cutout={70} />
          </div>
          <div className="card chart-card">
            <h4>Stock availability</h4>
            <DoughnutChart labels={["In stock", "Out of stock"]}
                           data={[p.stockAvailability.inStock, p.stockAvailability.outOfStock]}
                           backgroundColor={["#10b981", "#ef4444"]} cutout={70} />
          </div>
          <div className="card chart-card">
            <h4>Revenue distribution</h4>
            <DoughnutChart labels={["Tax", "Shipping", "Discount", "Net"]}
                           data={[p.revenueDistribution.totalTax, p.revenueDistribution.totalShippingCharges,
                                  p.revenueDistribution.totalDiscount, p.revenueDistribution.totalIncome]}
                           backgroundColor={["#8b5cf6", "#f59e0b", "#ef4444", "#10b981"]} cutout={70} />
          </div>
          <div className="card chart-card">
            <h4>Customers by age</h4>
            <DoughnutChart labels={["Under 20", "20–40", "Over 40"]}
                           data={[p.ageGroup.teen, p.ageGroup.adult, p.ageGroup.old]}
                           backgroundColor={["#38bdf8", "#6366f1", "#a855f7"]} cutout={70} />
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>New products & users (6 months)</h4>
            <BarChart data_1={b.products} data_2={b.users} title_1="Products" title_2="Users"
                      bgColor_1="rgba(99,102,241,0.8)" bgColor_2="rgba(56,189,248,0.8)" labels={six} />
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>Revenue (12 months)</h4>
            <LineChart data={l.revenue} label="Revenue" labels={twelve}
                       backgroundColor="rgba(16,185,129,0.15)" borderColor="#10b981" />
          </div>
          <div className="card chart-card chart-card--wide">
            <h4>Discount given (12 months)</h4>
            <LineChart data={l.discount} label="Discount" labels={twelve}
                       backgroundColor="rgba(239,68,68,0.15)" borderColor="#ef4444" />
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
                    <td>{formatINR(t.discount)}</td>
                    <td>{formatINR(t.amount)}</td>
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
