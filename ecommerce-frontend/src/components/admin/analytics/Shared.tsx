import { useState } from "react";
import toast from "react-hot-toast";
import { LuDownload, LuTrendingDown, LuTrendingUp } from "react-icons/lu";
import { getToken } from "../../../redux/api/baseQuery";
import { server } from "../../../redux/config";
import { SectionName } from "../../../types/analytics";
import { AudienceFilter, audienceName, HIDDEN_CELL } from "../../../utils/sections";
import { csvPath, fileNameFrom } from "./sections";

export const ChangeBadge = ({ change, against }: { change?: number | null; against: string }) =>
  change == null ? null : (
    <small className={change >= 0 ? "green" : "red"}>
      {change >= 0 ? <LuTrendingUp aria-hidden /> : <LuTrendingDown aria-hidden />}
      <span className="sr-only">{change >= 0 ? "Up" : "Down"}</span>
      {change > 0 ? "+" : ""}{change}% <em>{against}</em>
    </small>
  );

/** One figure. `lead` makes it the largest on the row; the change reads as words too, not colour alone. */
export const Kpi = ({ label, value, change, against = "", lead }: {
  label: string; value: string; change?: number | null; against?: string; lead?: boolean;
}) => (
  <article className={`kpi${lead ? " kpi--lead" : ""}`}>
    <span>{label}</span>
    <strong>{value}</strong>
    <ChangeBadge change={change} against={against} />
  </article>
);

/**
 * What a view narrowed to an audience segment counts, or that it is hidden. Hidden uses the audience table's neutral
 * label: saying why (too few buyers, or protecting another group) would itself give something away.
 */
export const AudienceNote = ({ audience, hidden, previousHidden, comparison }: {
  audience: AudienceFilter; hidden: boolean; previousHidden: boolean; comparison: string;
}) => {
  const name = audienceName(audience);
  if (!name) return null;
  if (hidden) {
    return (
      <p className="data-table__empty" role="status">
        {name}: {HIDDEN_CELL} in this range. Groups are shown only when the Customers tab&apos;s audience table shows
        them, so that no small group can be worked out.
      </p>
    );
  }
  return (
    <p className="analytics__note">
      Only orders by signed-in buyers who are {name}. Age is counted on the range&apos;s last day, and on the last day
      of the {comparison} for it, so a birthday can move a buyer up one band.
      {previousHidden && ` The ${comparison} is ${HIDDEN_CELL} for this group, so it is not shown.`}
    </p>
  );
};

export const SectionState = ({ loading }: { loading?: boolean }) => (
  <p className="data-table__empty" role="status">
    {loading ? "Loading…" : "Could not load this section. Please retry."}
  </p>
);

export const CsvButton = ({ section, params }: {
  section: SectionName; params: Record<string, string | number | undefined | null>;
}) => {
  const [busy, setBusy] = useState(false);
  const download = async () => {
    setBusy(true);
    try {
      const res = await fetch(`${server}${csvPath(section, params)}`, {
        headers: { Authorization: `Bearer ${getToken() ?? ""}` },
      });
      if (!res.ok) throw new Error(String(res.status));
      const url = URL.createObjectURL(await res.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = fileNameFrom(res.headers.get("Content-Disposition"), `${section}.csv`);
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      toast.error("Could not export this section");
    } finally {
      setBusy(false);
    }
  };
  return (
    <button type="button" className="btn" onClick={download} disabled={busy}>
      <LuDownload aria-hidden /> {busy ? "Exporting…" : "Export CSV"}
    </button>
  );
};
