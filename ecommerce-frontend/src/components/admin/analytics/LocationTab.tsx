import { useSearchParams } from "react-router-dom";
import { useGetLocationSectionQuery } from "../../../redux/api/dashboardAPI";
import { LocationParams, SectionParams } from "../../../types/analytics";
import { formatMoney } from "../../../utils/features";
import { COMPARE_LABEL, drillTo } from "./sections";
import { ChangeBadge, CsvButton, SectionState } from "./Shared";

const LocationTab = ({ params }: { params: SectionParams }) => {
  const [search, setSearch] = useSearchParams();
  const query: LocationParams = {
    ...params,
    country: search.get("country") ?? undefined,
    state: search.get("state") ?? undefined,
    pinPrefix: search.get("pinPrefix") ?? undefined,
  };
  const { data, isLoading, isError } = useGetLocationSectionQuery(query);
  const go = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(search);
    Object.entries(changes).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    setSearch(next, { replace: true });
  };
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const against = COMPARE_LABEL[params.compare];

  return (
    <>
      <div className="analytics__actions">
        <nav className="breadcrumb" aria-label="Location drill-down">
          <button type="button" className="link" onClick={() => go({ country: null, state: null, pinPrefix: null })}>All countries</button>
          {s.country && <> › <button type="button" className="link" onClick={() => go({ state: null, pinPrefix: null })}>{s.country}</button></>}
          {s.state && <> › <button type="button" className="link" onClick={() => go({ pinPrefix: null })}>{s.state}</button></>}
          {s.pinPrefix && <> › {s.pinPrefix}</>}
        </nav>
        <CsvButton section="location" params={query} />
      </div>
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide location-table">
          <h4>Orders by {s.level === "pin_prefix" ? "PIN area (first 3 digits)" : s.level === "pin" ? "PIN code" : s.level}</h4>
          {s.rows.length === 0 ? <p className="data-table__empty">No orders shipped here in this period.</p> : (
            <table className="mini-table">
              <thead><tr><th>Region</th><th>Orders</th><th>Share</th><th>Revenue</th><th>Avg order</th><th>Change</th></tr></thead>
              <tbody>
                {s.rows.map((r) => {
                  const next = drillTo(s.level, r.key);
                  return (
                    <tr key={r.key} className={r.small ? "is-muted" : undefined}>
                      <td>{next ? <button type="button" className="link" onClick={() => go(next)}>{r.label}</button> : r.label}</td>
                      <td>{r.orders}</td><td>{r.share}%</td><td>{formatMoney(r.revenue)}</td>
                      <td>{formatMoney(r.averageOrderValue)}</td>
                      <td>{r.small ? <small>too few orders</small> : <ChangeBadge change={r.change} against={against} />}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          <p className="analytics__note">Regions with fewer than 3 orders are greyed out and get no change figure. Exports stop at the 3-digit PIN area.</p>
        </div>
        {s.unrecognised && s.unrecognised.orders > 0 && (
          <div className="card chart-card location-table">
            <h4>States we could not recognise ({s.unrecognised.orders} orders)</h4>
            <ul className="category-bars">
              {s.unrecognised.samples.map((u) => <li key={u.raw}><span>“{u.raw}”</span><b>{u.orders}</b></li>)}
            </ul>
            <p className="analytics__note">These were typed at checkout before the state list existed.</p>
          </div>
        )}
        {s.topProducts.length > 0 && (
          <div className="card chart-card location-table">
            <h4>Top products here</h4>
            <table className="mini-table">
              <thead><tr><th>Product</th><th>Units</th><th>Revenue</th></tr></thead>
              <tbody>{s.topProducts.map((p) => (
                <tr key={p.productId}><td>{p.name}</td><td>{p.units}</td><td>{formatMoney(p.revenue)}</td></tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
};

export default LocationTab;
