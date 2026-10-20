import { ChangeEvent, useRef, useState } from "react";
import toast from "react-hot-toast";
import { LuDownload, LuUpload, LuX } from "react-icons/lu";
import { getToken } from "../../redux/api/baseQuery";
import { server } from "../../redux/config";
import { useImportProductsMutation } from "../../redux/api/productAPI";
import { CsvChange, CsvImportError } from "../../types/api-types";

type Report =
  | { kind: "preview"; file: File; changes: CsvChange[]; unchanged: number }
  | { kind: "errors"; message: string; errors: { line: number; message: string }[] };

const MAX_SHOWN = 50;

/** Export every product and variant as CSV, and import an edited file after a preview. */
const ProductCsvTools = () => {
  const input = useRef<HTMLInputElement>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [exporting, setExporting] = useState(false);
  const [importProducts, { isLoading }] = useImportProductsMutation();

  const open = (r: Report) => {
    setReport(r);
    dialog.current?.showModal();
  };
  const close = () => {
    dialog.current?.close();
    setReport(null);
  };

  const exportCsv = async () => {
    setExporting(true);
    try {
      const res = await fetch(`${server}/api/v1/product/export`, {
        headers: { Authorization: `Bearer ${getToken() ?? ""}` },
      });
      if (!res.ok) throw new Error(String(res.status));
      const url = URL.createObjectURL(await res.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = `products-${new Date().toISOString().slice(0, 10)}.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      toast.error("Could not export products");
    } finally {
      setExporting(false);
    }
  };

  const run = async (file: File, dryRun: boolean) => {
    const res = await importProducts({ file, dryRun });
    if ("error" in res) {
      const data = (res.error as { data?: CsvImportError }).data;
      return open({ kind: "errors", message: data?.message ?? "Import failed", errors: data?.errors ?? [] });
    }
    if (!dryRun) {
      close();
      return toast.success(`Updated ${res.data.changes.length} row${res.data.changes.length === 1 ? "" : "s"}`);
    }
    if (res.data.changes.length === 0) return toast("Nothing to change: the file matches the catalogue");
    open({ kind: "preview", file, changes: res.data.changes, unchanged: res.data.unchanged });
  };

  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (file.size > 1_000_000) return toast.error("The file must be 1 MB or smaller");
    run(file, true);
  };

  return (
    <>
      <button type="button" className="btn" onClick={exportCsv} disabled={exporting}>
        <LuDownload /> {exporting ? "Exporting…" : "Export CSV"}
      </button>
      <button type="button" className="btn" onClick={() => input.current?.click()} disabled={isLoading}>
        <LuUpload /> {isLoading ? "Checking…" : "Import CSV"}
      </button>
      <input ref={input} type="file" accept=".csv,text/csv" hidden onChange={onFile} />

      <dialog ref={dialog} className="csv-report" aria-labelledby="csv-report-title" onClose={() => setReport(null)}>
        <header>
          <h3 id="csv-report-title">
            {report?.kind === "errors" ? "Fix these rows and import again" : "Review changes before applying"}
          </h3>
          <button type="button" className="icon-btn" onClick={close} aria-label="Close">
            <LuX />
          </button>
        </header>

        {report?.kind === "errors" && (
          <>
            <p className="red">{report.message}</p>
            <ul className="csv-report__list">
              {report.errors.slice(0, MAX_SHOWN).map((e) => (
                <li key={`${e.line}-${e.message}`}>
                  <b>Line {e.line}</b> {e.message}
                </li>
              ))}
            </ul>
          </>
        )}

        {report?.kind === "preview" && (
          <>
            <p>
              {report.changes.length} row{report.changes.length === 1 ? "" : "s"} will change
              {report.unchanged > 0 && `, ${report.unchanged} unchanged`}. Nothing has been saved yet.
            </p>
            <ul className="csv-report__list">
              {report.changes.slice(0, MAX_SHOWN).map((c) => (
                <li key={c.line}>
                  <b>
                    {c.product}
                    {c.variant && ` · ${c.variant}`}
                  </b>{" "}
                  {c.changes.join(", ")}
                </li>
              ))}
              {report.changes.length > MAX_SHOWN && <li>…and {report.changes.length - MAX_SHOWN} more</li>}
            </ul>
            <footer>
              <button type="button" className="btn" onClick={close}>
                Cancel
              </button>
              <button type="button" className="btn btn--primary" onClick={() => run(report.file, false)} disabled={isLoading}>
                {isLoading ? "Applying…" : `Apply ${report.changes.length} change${report.changes.length === 1 ? "" : "s"}`}
              </button>
            </footer>
          </>
        )}
      </dialog>
    </>
  );
};

export default ProductCsvTools;
