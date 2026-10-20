import { LuTrendingDown, LuTrendingUp } from "react-icons/lu";
import { useNavigate, useSearchParams } from "react-router-dom";
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
  useGetSummaryQuery,
} from "../../redux/api/dashboardAPI";
import { useCategoriesQuery } from "../../redux/api/productAPI";
import { Summary } from "../../types/api-types";
import { SummaryParams } from "../../types/types";
import { PRESETS, Preset, presetRange } from "../../utils/dateRange";
import { formatINR, lastMonths } from "../../utils/features";

const COMPARE_LABEL = { previous: "vs previous period", year: "vs last year", none: "" } as const;

const Widget = ({ label, value, change, against }: {
  label: string; value: string; change?: number | null; against: string;
}) => (
  <article className="kpi">
    <span>{label}</span>
    <strong>{value}</strong>
    {change != null && (
      <small className={change >= 0 ? "green" : "red"}>
        {change >= 0 ? <LuTrendingUp /> : <LuTrendingDown />} {change > 0 ? "+" : ""}
        {change}% <em>{against}</em>
      </small>
    )}
  </article>
);

const MONTH_SHORT = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "5 Oct" for days and weeks, "Oct 2026" for months; the date string is read as is, never shifted by time zone. */
const bucketLabel = (start: string, granularity: Summary["granularity"]) => {
  const [y, m, d] = start.split("-").map(Number);
  return granularity === "month" ? `${MONTH_SHORT[m - 1]} ${y}` : `${d} ${MONTH_SHORT[m - 1]}`;
};

const Analytics = () => {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const category = params.get("category") ?? "";
  const preset = (params.get("range") as Preset) || "30d";
  const range = preset === "custom"
    ? { from: params.get("from") ?? presetRange("30d").from, to: params.get("to") ?? presetRange("30d").to }
    : presetRange(preset);
  const compare = (params.get("compare") as SummaryParams["compare"]) || "previous";
  const basis = (params.get("basis") as SummaryParams["basis"]) || "net";
  const setParam = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    setParams(next, { replace: true });
  };

  const scope = { category };
  const { data: categories } = useCategoriesQuery();
  const summary = useGetSummaryQuery({ ...scope, ...range, compare, basis });
  const stats = useGetDashboardStatsQuery(scope);
  const pie = useGetPieChartQuery(scope);
  const bar = useGetBarChartQuery(scope);
  const line = useGetLineChartQuery(scope);

  if (summary.isLoading || stats.isLoading || pie.isLoading || bar.isLoading || line.isLoading) return <Loader />;
  if (summary.isError || stats.isError || !summary.data || !stats.data || !pie.data || !bar.data || !line.data) {
    return (
      <AdminLayout>
        <p className="data-table__empty">Could not load analytics. Please retry.</p>
      </AdminLayout>
    );
  }

  const sum = summary.data.summary;
  const now = sum.current;
  const change = sum.changePercent;
  const against = COMPARE_LABEL[compare ?? "previous"];
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
          <div className="analytics__filters">
            <select value={preset} onChange={(e) => setParam(e.target.value === "custom"
                      ? { range: "custom", from: range.from, to: range.to }
                      : { range: e.target.value, from: "", to: "" })}
                    aria-label="Date range">
              {PRESETS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
            </select>
            {preset === "custom" && (
              <>
                <input type="date" value={range.from} max={range.to} aria-label="From"
                       onChange={(e) => e.target.value && setParam({ from: e.target.value })} />
                <input type="date" value={range.to} min={range.from} aria-label="To"
                       onChange={(e) => e.target.value && setParam({ to: e.target.value })} />
              </>
            )}
            <select value={compare} onChange={(e) => setParam({ compare: e.target.value })} aria-label="Compare with">
              <option value="previous">Compare: previous period</option>
              <option value="year">Compare: same period last year</option>
              <option value="none">No comparison</option>
            </select>
            <select value={basis} onChange={(e) => setParam({ basis: e.target.value })} aria-label="Revenue basis">
              <option value="net">Net revenue (after refunds)</option>
              <option value="gross">Gross revenue</option>
            </select>
            <select value={category} onChange={(e) => setParam({ category: e.target.value })}
                    aria-label="Scope by category">
              <option value="">All categories</option>
              {categories?.categories.map((c) => (
                <option key={c} value={c}>{c}</option>
              ))}
            </select>
          </div>
        </header>

        <section className="kpi-row">
          <Widget label={basis === "net" ? "Net revenue" : "Gross revenue"} value={formatINR(now.revenue)}
                  change={change?.revenue} against={against} />
          <Widget label="Orders" value={String(now.orders)} change={change?.orders} against={against} />
          <Widget label="Average order value" value={formatINR(now.averageOrderValue)}
                  change={change?.averageOrderValue} against={against} />
          <Widget label="Units sold" value={String(now.units)} change={change?.units} against={against} />
        </section>
        <p className="analytics__note">
          {now.refunds != null && <>Refunds {formatINR(now.refunds)} · </>}
          {now.discount != null && <>Discounts {formatINR(now.discount)} · </>}
          {now.tax != null && <>Tax collected {formatINR(now.tax)} · </>}
          {category && <>Revenue counts only items in this category · </>}
          Days in {sum.timezone}
        </p>

        <section className="analytics__grid">
          <div className="card chart-card chart-card--wide">
            <h4>Revenue & orders by {sum.granularity}</h4>
            <BarChart data_1={sum.series.map((p) => p.revenue)} data_2={sum.series.map((p) => p.orders)}
                      title_1="Revenue" title_2="Orders" dualAxis bgColor_1={c.series[0]} bgColor_2={c.series[1]}
                      labels={sum.series.map((p) => bucketLabel(p.start, sum.granularity))} />
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
