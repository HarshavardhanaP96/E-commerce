import { useSearchParams } from "react-router-dom";
import AdminLayout from "../../components/admin/AdminLayout";
import AnalyticsSection from "../../components/admin/AnalyticsSections";
import OrdersTab from "../../components/admin/analytics/OrdersTab";
import OverviewTab from "../../components/admin/analytics/OverviewTab";
import SalesTab from "../../components/admin/analytics/SalesTab";
import { isRangeOnly, TABS, tabFrom } from "../../components/admin/analytics/sections";
import { useCategoriesQuery } from "../../redux/api/productAPI";
import { SectionParams } from "../../types/analytics";
import { SummaryParams } from "../../types/types";
import { PRESETS, Preset, presetRange } from "../../utils/dateRange";

const Analytics = () => {
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

  const { data: categories } = useCategoriesQuery();

  const tab = tabFrom(params.get("tab"));
  // Step 3's tabs read only the date range; the other filters would do nothing there.
  const rangeOnly = isRangeOnly(tab);
  const section: SectionParams = { from: range.from, to: range.to, compare: compare ?? "previous",
                                   basis: basis ?? "net", category: category || undefined };

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
            {!rangeOnly && (
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
          <nav className="tabs analytics__tabs" role="tablist" aria-label="Analytics sections"
               ref={(el) => el?.querySelector(".is-active")?.scrollIntoView({ block: "nearest", inline: "nearest" })}>
            {TABS.map((t) => (
              <button key={t.id} type="button" role="tab" aria-selected={tab === t.id}
                      className={tab === t.id ? "is-active" : undefined}
                      onClick={() => setParam({ tab: t.id === "overview" ? "" : t.id })}>
                {t.label}
              </button>
            ))}
          </nav>
        </header>
        {rangeOnly && (
          <p className="analytics__note">
            {range.from} to {range.to}, in the store&apos;s time zone. Revenue here is the value of orders placed.
          </p>
        )}
        <div className="analytics__body" role="tabpanel">
          {tab === "overview" && <OverviewTab params={section} />}
          {tab === "sales" && <SalesTab params={section} />}
          {tab === "orders" && <OrdersTab params={section} />}
          {rangeOnly && <AnalyticsSection tab={tab} range={range} />}
        </div>
      </div>
    </AdminLayout>
  );
};

export default Analytics;
