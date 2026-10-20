import { useGetSalesQuery } from "../../../redux/api/dashboardAPI";
import { SectionParams } from "../../../types/analytics";
import { formatMoney } from "../../../utils/features";
import { BarChart, DoughnutChart } from "../Charts";
import { chartColors as c } from "../chartTheme";
import { COMPARE_LABEL, alignPrevious, bucketLabel } from "./sections";
import { ChangeBadge, CsvButton, SectionState } from "./Shared";

const SalesTab = ({ params }: { params: SectionParams }) => {
  const { data, isLoading, isError } = useGetSalesQuery(params);
  if (isLoading) return <SectionState loading />;
  if (isError || !data) return <SectionState />;
  const s = data.section;
  const against = COMPARE_LABEL[params.compare];
  const previous = alignPrevious(s.series, s.previousSeries);
  const b = s.breakdown;

  return (
    <>
      <div className="analytics__actions"><CsvButton section="sales" params={params} /></div>
      {!b && <p className="analytics__note">Breakdown and payment methods cover whole orders, so they are hidden while a category is selected.</p>}
      <section className="analytics__grid">
        <div className="card chart-card chart-card--wide">
          <h4>{s.basis === "net" ? "Net revenue" : "Gross revenue"} by {s.granularity}</h4>
          <BarChart data_1={s.series.map((p) => p.revenue)} data_2={previous}
                    title_1="This period" title_2={s.previousSeries ? "Comparison period" : ""}
                    bgColor_1={c.series[0]} bgColor_2={c.series[2]}
                    labels={s.series.map((p) => bucketLabel(p.start, s.granularity))} />
        </div>
        <div className="card chart-card chart-card--wide">
          <h4>Revenue by category</h4>
          <ul className="category-bars category-bars--money">
            {s.byCategory.map((row) => (
              <li key={row.category}>
                <span>{row.category}</span>
                <div><i style={{ width: `${row.share}%` }} /></div>
                <b>{formatMoney(row.revenue)}</b>
                <ChangeBadge change={row.change} against={against} />
              </li>
            ))}
            {s.byCategory.length === 0 && <li className="category-bars__empty">No sales in this period</li>}
          </ul>
        </div>
        {b && (
          <div className="card chart-card chart-card--wide sales-breakdown">
            <h4>Where the money went</h4>
            <table className="mini-table">
              <tbody>
                <tr><th scope="row">Charged to customers</th><td>{formatMoney(b.charged)}</td></tr>
                <tr><th scope="row">…of which tax</th><td>{formatMoney(b.tax)}</td></tr>
                <tr><th scope="row">…of which shipping</th><td>{formatMoney(b.shipping)}</td></tr>
                <tr><th scope="row">Discounts given</th><td>{formatMoney(b.discount)}</td></tr>
                <tr><th scope="row">Refunds</th><td>{formatMoney(b.refunds)}</td></tr>
                <tr><th scope="row">Net revenue</th><td><b>{formatMoney(b.net)}</b></td></tr>
                <tr><th scope="row">Orders with a coupon</th><td>{b.couponOrders} ({b.couponOrderShare}%)</td></tr>
              </tbody>
            </table>
          </div>
        )}
        {s.byPaymentMethod && s.byPaymentMethod.length > 0 && (
          <div className="card chart-card">
            <h4>Payment methods</h4>
            <DoughnutChart labels={s.byPaymentMethod.map((m) => m.method.toUpperCase())}
                           data={s.byPaymentMethod.map((m) => m.orders)}
                           backgroundColor={s.byPaymentMethod.map((_, i) => c.series[i % c.series.length])}
                           cutout="72%" />
          </div>
        )}
      </section>
    </>
  );
};

export default SalesTab;
