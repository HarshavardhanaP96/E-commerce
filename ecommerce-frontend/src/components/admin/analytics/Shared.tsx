import { useState } from "react";
import toast from "react-hot-toast";
import { LuDownload, LuTrendingDown, LuTrendingUp } from "react-icons/lu";
import { getToken } from "../../../redux/api/baseQuery";
import { server } from "../../../redux/config";
import { SectionName } from "../../../types/analytics";
import { csvPath, fileNameFrom } from "./sections";

export const ChangeBadge = ({ change, against }: { change?: number | null; against: string }) =>
  change == null ? null : (
    <small className={change >= 0 ? "green" : "red"}>
      {change >= 0 ? <LuTrendingUp /> : <LuTrendingDown />} {change > 0 ? "+" : ""}
      {change}% <em>{against}</em>
    </small>
  );

export const Kpi = ({ label, value, change, against = "" }: {
  label: string; value: string; change?: number | null; against?: string;
}) => (
  <article className="kpi">
    <span>{label}</span>
    <strong>{value}</strong>
    <ChangeBadge change={change} against={against} />
  </article>
);

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
