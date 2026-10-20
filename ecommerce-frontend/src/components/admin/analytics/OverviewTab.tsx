import { useNavigate } from "react-router-dom";
import { useGetOverviewQuery } from "../../../redux/api/dashboardAPI";
import { useLowStockQuery } from "../../../redux/api/productAPI";
import { SectionParams } from "../../../types/analytics";
import { formatMoney, transformImage } from "../../../utils/features";
import { BarChart } from "../Charts";
import { chartColors as c } from "../chartTheme";
import { COMPARE_LABEL, HOURS, WEEKDAYS, bucketLabel, heatLevel } from "./sections";
import { CsvButton, Kpi, SectionState } from "./Shared";

const OverviewTab = ({ params }: { params: SectionParams }) => {
  const navigate = useNavigate();
  const { data, isLoading, isError } = useGetOverviewQuery(params);
  const { data: lowStock } = useLowStockQuery();
  // Not narrowed by the category filter: alerts are store-wide.
  const alerts = lowStock?.alerts ?? [];
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const now = s.current;
  const against = COMPARE_LABEL[params.compare];
  const maxHeat = Math.max(0, ...s.heat.flat());

  return (
    <>
      <div className="analytics__actions"><CsvButton section="overview" params={params} /></div>
      <section className="kpi-row">
        <Kpi label={s.basis === "net" ? "Net revenue" : "Gross revenue"} value={formatMoney(now.revenue)}
             change={s.change?.revenue} against={against} />
        <Kpi label="Orders" value={String(now.orders)} change={s.change?.orders} against={against} />
        <Kpi label="Average order value" value={formatMoney(now.averageOrderValue)}
             change={s.change?.averageOrderValue} against={against} />
        <Kpi label="Units sold" value={String(now.units)} change={s.change?.units} against={against} />
      </section>
      <p className="analytics__note">
        {now.refunds != null && <>Refunds {formatMoney(now.refunds)} · </>}
        {now.discount != null && <>Discounts {formatMoney(now.discount)} · </>}
        {now.tax != null && <>Tax collected {formatMoney(now.tax)} · </>}
        {params.category && <>Revenue counts only items in this category · </>}
        Days in {s.timezone}
      </p>
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>Revenue & orders by {s.granularity}</h4>
          <BarChart data_1={s.series.map((p) => p.revenue)} data_2={s.series.map((p) => p.orders)}
                    title_1="Revenue" title_2="Orders" dualAxis bgColor_1={c.series[0]} bgColor_2={c.series[1]}
                    labels={s.series.map((p) => bucketLabel(p.start, s.granularity))} />
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Sales by weekday (before refunds)</h4>
          <BarChart data_1={s.byWeekday.map((d) => d.revenue)} data_2={s.byWeekday.map((d) => d.orders)}
                    title_1="Sales" title_2="Orders" dualAxis bgColor_1={c.series[0]} bgColor_2={c.series[1]}
                    labels={WEEKDAYS} />
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>When orders come in ({s.timezone})</h4>
          <div className="heatmap" role="table" aria-label="Orders by weekday and hour">
            <div role="row" className="heatmap__row heatmap__head">
              <span role="columnheader" />
              {HOURS.map((h) => <span role="columnheader" key={h}>{h % 3 === 0 ? h : ""}</span>)}
            </div>
            {s.heat.map((row, d) => (
              <div role="row" className="heatmap__row" key={WEEKDAYS[d]}>
                <span role="rowheader">{WEEKDAYS[d]}</span>
                {row.map((n, h) => (
                  <span role="cell" key={h} className={`heatmap__cell l${heatLevel(n, maxHeat)}`}
                        title={`${WEEKDAYS[d]} ${h}:00 – ${n} orders`} aria-label={`${n} orders`} />
                ))}
              </div>
            ))}
          </div>
        </div>
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
      </section>
    </>
  );
};

export default OverviewTab;
