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
import { useCategoriesQuery, useLowStockQuery } from "../../redux/api/productAPI";
import { SummaryParams } from "../../types/types";
import { PRESETS, Preset, bucketLabel, presetRange } from "../../utils/dateRange";
import { SECTION_TABS, SectionTab } from "../../utils/sections";
import AnalyticsSection from "../../components/admin/AnalyticsSections";
import { formatMoney, lastMonths, transformImage } from "../../utils/features";

const COMPARE_LABEL = { previous: "vs previous period", year: "vs last year", none: "" } as const;

/** One dashboard figure. `lead` makes it the largest on the row; the change reads as words too, not colour alone. */
const Widget = ({ label, value, change, against, lead }: {
  label: string; value: string; change?: number | null; against: string; lead?: boolean;
}) => (
  <article className={`kpi${lead ? " kpi--lead" : ""}`}>
    <span>{label}</span>
    <strong>{value}</strong>
    {change != null && (
      <small className={change >= 0 ? "green" : "red"}>
        {change >= 0 ? <LuTrendingUp aria-hidden /> : <LuTrendingDown aria-hidden />}
        <span className="sr-only">{change >= 0 ? "Up" : "Down"}</span>
        {change > 0 ? "+" : ""}{change}% <em>{against}</em>
      </small>
    )}
  </article>
);


/** "5 Oct" for days and weeks, "Oct 2026" for months; the date string is read as is, never shifted by time zone. */
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

  const tab = SECTION_TABS.some((t) => t.value === params.get("tab")) ? params.get("tab") as SectionTab : "overview";
  const overview = tab === "overview";

  const scope = { category };
  const skip = { skip: !overview };
  const { data: categories } = useCategoriesQuery();
  const summary = useGetSummaryQuery({ ...scope, ...range, compare, basis }, skip);
  const stats = useGetDashboardStatsQuery(scope, skip);
  const pie = useGetPieChartQuery(scope, skip);
  const bar = useGetBarChartQuery(scope, skip);
  const line = useGetLineChartQuery(scope, skip);
  const { data: lowStock } = useLowStockQuery(undefined, skip);
  // Not narrowed by the category filter: alerts are store-wide.
  const alerts = lowStock?.alerts ?? [];

  const toolbar = (
    <header className="analytics__toolbar">
      <h1>Analytics</h1>
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
        {overview && (
          <>
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
          </>
        )}
      </div>
      <nav className="tabs analytics__tabs" aria-label="Analytics sections"
           ref={(el) => el?.querySelector(".is-active")?.scrollIntoView({ block: "nearest", inline: "nearest" })}>
        {SECTION_TABS.map((t) => (
          <button key={t.value} type="button" className={t.value === tab ? "is-active" : undefined}
                  aria-current={t.value === tab ? "page" : undefined}
                  onClick={() => setParam({ tab: t.value === "overview" ? "" : t.value })}>
            {t.label}
          </button>
        ))}
      </nav>
    </header>
  );

  if (!overview) {
    return (
      <AdminLayout>
        <div className="analytics">
          {toolbar}
          <p className="analytics__note">
            {range.from} to {range.to}, in the store&apos;s time zone. Revenue here is the value of orders placed.
          </p>
          <AnalyticsSection tab={tab} range={range} />
        </div>
      </AdminLayout>
    );
  }

  if (summary.isLoading || stats.isLoading || pie.isLoading || bar.isLoading || line.isLoading) return <Loader />;
  if (summary.isError || stats.isError || !summary.data || !stats.data || !pie.data || !bar.data || !line.data) {
    return (
      <AdminLayout title="Analytics">
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
        {toolbar}

        <section className="kpi-row">
          <Widget label={basis === "net" ? "Net revenue" : "Gross revenue"} value={formatMoney(now.revenue)}
                  change={change?.revenue} against={against} lead />
          <Widget label="Orders" value={String(now.orders)} change={change?.orders} against={against} />
          <Widget label="Average order value" value={formatMoney(now.averageOrderValue)}
                  change={change?.averageOrderValue} against={against} />
          <Widget label="Units sold" value={String(now.units)} change={change?.units} against={against} />
        </section>
        <p className="analytics__note">
          {now.refunds != null && <>Refunds {formatMoney(now.refunds)} · </>}
          {now.discount != null && <>Discounts {formatMoney(now.discount)} · </>}
          {now.tax != null && <>Tax collected {formatMoney(now.tax)} · </>}
          {category && <>Revenue counts only items in this category · </>}
          Days in {sum.timezone}
        </p>

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
                       backgroundColor={c.fill(c.series[0])} borderColor={c.series[0]} />
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
