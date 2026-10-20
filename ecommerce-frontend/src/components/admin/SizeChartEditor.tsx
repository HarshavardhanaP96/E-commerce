import { useState } from "react";
import toast from "react-hot-toast";
import { LuPlus, LuX } from "react-icons/lu";
import { errorMessage } from "../../redux/api/baseQuery";
import { useReplaceSizeChartMutation } from "../../redux/api/productAPI";
import { Product } from "../../types/types";

const MAX_COLUMNS = 8;
const MAX_ROWS = 30;

const TEMPLATES: Record<string, string[]> = {
  Clothing: ["Size", "Chest (in)", "Waist (in)", "Length (in)"],
  Footwear: ["UK", "EU", "US", "Foot length (cm)"],
};

/** The product's sizes (from a "Size" option), to pre-fill the first column. */
const sizesOf = (product: Product) =>
  product.variants?.axes.find((a) => /^size$/i.test(a.name))?.values.map((v) => v.label) ?? [];

/** Editable measurement table shown to shoppers as the product's size guide. */
const SizeChartEditor = ({ product }: { product: Product }) => {
  const [columns, setColumns] = useState<string[]>(product.sizeChart?.columns ?? []);
  const [rows, setRows] = useState<string[][]>(product.sizeChart?.rows ?? []);
  const [note, setNote] = useState(product.sizeChart?.note ?? "");
  const [save, { isLoading }] = useReplaceSizeChartMutation();

  const start = (headings: string[]) => {
    const sizes = sizesOf(product);
    setColumns(headings);
    setRows((sizes.length ? sizes : [""]).map((s) => [s, ...headings.slice(1).map(() => "")]));
  };

  const setHeading = (i: number, value: string) => setColumns(columns.map((c, j) => (j === i ? value : c)));
  const addColumn = () => {
    if (columns.length >= MAX_COLUMNS) return;
    setColumns([...columns, ""]);
    setRows(rows.map((r) => [...r, ""]));
  };
  const removeColumn = (i: number) => {
    setColumns(columns.filter((_, j) => j !== i));
    setRows(rows.map((r) => r.filter((_, j) => j !== i)));
  };
  const setCell = (r: number, c: number, value: string) =>
    setRows(rows.map((row, i) => (i === r ? row.map((v, j) => (j === c ? value : v)) : row)));
  const addRow = () => rows.length < MAX_ROWS && setRows([...rows, columns.map(() => "")]);
  const removeRow = (i: number) => setRows(rows.filter((_, j) => j !== i));

  const submit = async (body: { columns: string[]; rows: string[][]; note?: string }) => {
    const res = await save({ productId: product._id, body });
    if ("error" in res) return toast.error(errorMessage(res.error));
    const chart = res.data.product.sizeChart;
    setColumns(chart?.columns ?? []);
    setRows(chart?.rows ?? []);
    setNote(chart?.note ?? "");
    toast.success(res.data.message);
  };

  const onSave = () => {
    if (columns.some((c) => !c.trim())) return toast.error("Every column needs a heading");
    const filled = rows.filter((r) => r.some((v) => v.trim()));
    if (columns.length && !filled.length) return toast.error("Add at least one row");
    submit({ columns, rows: filled, note: note.trim() || undefined });
  };

  const onRemove = () => {
    if (!confirm("Remove the size guide from this product?")) return;
    submit({ columns: [], rows: [] });
  };

  return (
    <section className="card card--scroll size-chart-editor">
      <p className="variant-editor__intro">
        Shoppers see this table behind a “Size guide” link on the product page. Put the size in the first column.
      </p>

      {columns.length === 0 ? (
        <div className="variant-editor__add">
          {Object.entries(TEMPLATES).map(([name, headings]) => (
            <button key={name} type="button" className="btn" onClick={() => start(headings)}>
              Start from {name.toLowerCase()} template
            </button>
          ))}
          <button type="button" className="btn" onClick={() => start(["Size", ""])}>
            <LuPlus /> Blank table
          </button>
        </div>
      ) : (
        <>
          <div className="variant-table">
            <table>
              <thead>
                <tr>
                  {columns.map((c, i) => (
                    <th key={i}>
                      <span className="size-chart-editor__head">
                        <input value={c} onChange={(e) => setHeading(i, e.target.value)} maxLength={30} placeholder="Heading" aria-label={`Column ${i + 1} heading`} />
                        {columns.length > 1 && (
                          <button type="button" className="icon-btn" onClick={() => removeColumn(i)} aria-label={`Remove column ${c || i + 1}`}>
                            <LuX />
                          </button>
                        )}
                      </span>
                    </th>
                  ))}
                  <th aria-hidden />
                </tr>
              </thead>
              <tbody>
                {rows.map((row, r) => (
                  <tr key={r}>
                    {row.map((cell, c) => (
                      <td key={c}>
                        <input value={cell} onChange={(e) => setCell(r, c, e.target.value)} maxLength={30} aria-label={`${columns[c] || "Column " + (c + 1)}, row ${r + 1}`} />
                      </td>
                    ))}
                    <td>
                      <button type="button" className="icon-btn" onClick={() => removeRow(r)} aria-label={`Remove row ${r + 1}`}>
                        <LuX />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="variant-editor__add">
            <button type="button" className="btn" onClick={addRow} disabled={rows.length >= MAX_ROWS}>
              <LuPlus /> Add row
            </button>
            <button type="button" className="btn" onClick={addColumn} disabled={columns.length >= MAX_COLUMNS}>
              <LuPlus /> Add column
            </button>
          </div>
          <label className="size-chart-editor__note">
            Note under the table (optional)
            <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={300} placeholder="e.g. Measured flat; if between sizes, size up." />
          </label>
        </>
      )}

      <footer className="variant-editor__footer">
        <span>{columns.length ? `${rows.length} row${rows.length === 1 ? "" : "s"} · ${columns.length} columns` : "No size guide"}</span>
        <div>
          {product.sizeChart && (
            <button type="button" className="btn btn--danger" onClick={onRemove} disabled={isLoading}>
              Remove size guide
            </button>
          )}
          {columns.length > 0 && (
            <button type="button" className="btn btn--primary" onClick={onSave} disabled={isLoading}>
              {isLoading ? "Saving…" : "Save size guide"}
            </button>
          )}
        </div>
      </footer>
    </section>
  );
};

export default SizeChartEditor;
