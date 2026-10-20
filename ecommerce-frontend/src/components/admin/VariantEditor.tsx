import { KeyboardEvent, useState } from "react";
import toast from "react-hot-toast";
import { LuPlus, LuX } from "react-icons/lu";
import { errorMessage } from "../../redux/api/baseQuery";
import { useAdjustVariantStockMutation, useReplaceVariantsMutation } from "../../redux/api/productAPI";
import { useGetVariantSalesQuery } from "../../redux/api/dashboardAPI";
import { Product } from "../../types/types";
import { VariantsRequest } from "../../types/api-types";
import { formatINR, transformImage } from "../../utils/features";

type ValueDraft = { label: string; hex: string; images: string[] };
type AxisDraft = { name: string; type: "TEXT" | "COLOUR"; values: ValueDraft[] };
type RowDraft = {
  id?: string;
  sku: string;
  options: Record<string, string>;
  price: string;
  mrp: string;
  stock: string;
  /** Stock as last saved on the server, to see whether the cell was edited. */
  savedStock?: number;
  status: "ACTIVE" | "DISABLED";
};

const MAX_AXES = 3;
const MAX_VARIANTS = 100;
const PRESETS = ["Size", "Colour", "Material", "Fit", "Capacity", "Pack size", "Flavour", "Pattern"];
const DEFAULT_HEX = "#cccccc";

const isColourName = (name: string) => /^colou?r$/i.test(name.trim());

const comboKey = (axes: AxisDraft[], options: Record<string, string>) => axes.map((a) => options[a.name] ?? "").join("\u0000");

/** Every combination of the axes' values, in axis order. */
const combinations = (axes: AxisDraft[]): Record<string, string>[] =>
  axes
    .filter((a) => a.values.length > 0)
    .reduce<Record<string, string>[]>(
      (acc, axis) => acc.flatMap((c) => axis.values.map((v) => ({ ...c, [axis.name]: v.label }))),
      [{}]
    );

/** Rows for the current axes: rows that still match a combination are kept, new combinations get a fresh row. */
const regenerate = (axes: AxisDraft[], rows: RowDraft[], defaultPrice: string): RowDraft[] => {
  if (axes.length === 0 || axes.some((a) => a.values.length === 0)) return [];
  const existing = new Map<string, RowDraft>();
  rows.forEach((r) => {
    const k = comboKey(axes, r.options);
    if (!existing.has(k)) existing.set(k, r);
  });
  const price = rows[0]?.price ?? defaultPrice;
  return combinations(axes).map(
    (options) =>
      existing.get(comboKey(axes, options)) ?? { sku: "", options, price, mrp: "", stock: "0", status: "ACTIVE" as const }
  );
};

const fromProduct = (product: Product): { axes: AxisDraft[]; rows: RowDraft[] } => {
  const v = product.variants ?? { axes: [], items: [] };
  return {
    axes: v.axes.map((a) => ({
      name: a.name,
      type: a.type,
      values: a.values.map((x) => ({ label: x.label, hex: x.hex ?? DEFAULT_HEX, images: x.images })),
    })),
    rows: v.items.map((i) => ({
      id: i.id,
      sku: i.sku,
      options: Object.fromEntries(i.options.map((o) => [o.name, o.value])),
      price: String(i.price),
      mrp: i.mrp != null ? String(i.mrp) : "",
      stock: String(i.stock),
      savedStock: i.stock,
      status: i.status,
    })),
  };
};

const VariantEditor = ({ product }: { product: Product }) => {
  const initial = fromProduct(product);
  const [axes, setAxes] = useState<AxisDraft[]>(initial.axes);
  const [rows, setRows] = useState<RowDraft[]>(initial.rows);
  const [draftValue, setDraftValue] = useState<Record<number, string>>({});
  const [imagePicker, setImagePicker] = useState<string>("");
  const [saveVariants, { isLoading: saving }] = useReplaceVariantsMutation();
  const [adjustStock] = useAdjustVariantStockMutation();
  const { data: salesData } = useGetVariantSalesQuery(product._id);
  const sales = new Map((salesData?.sales ?? []).map((s) => [s.variantId ?? "", s]));
  const lowAt = product.lowStockAt ?? 5;

  const gallery = [...new Set([product.photo, ...(product.images ?? [])].filter(Boolean))];
  const defaultPrice = String(product.price);
  const totalStock = rows.reduce((n, r) => n + (Number(r.stock) || 0), 0);
  const activePrices = rows.filter((r) => r.status === "ACTIVE").map((r) => Number(r.price)).filter((p) => p > 0);

  const update = (nextAxes: AxisDraft[], nextRows = rows) => {
    setAxes(nextAxes);
    setRows(regenerate(nextAxes, nextRows, defaultPrice));
  };

  const setAxis = (index: number, patch: Partial<AxisDraft>) => {
    const old = axes[index];
    const next = axes.map((a, i) => (i === index ? { ...a, ...patch } : a));
    // Renaming an axis re-keys the rows so their prices and stock survive.
    const renamed =
      patch.name !== undefined && patch.name !== old.name
        ? rows.map((r) => {
            const { [old.name]: value, ...rest } = r.options;
            return { ...r, options: { ...rest, [patch.name as string]: value } };
          })
        : rows;
    update(next, renamed);
  };

  const addAxis = (name = "") => {
    if (axes.length >= MAX_AXES) return;
    update([...axes, { name, type: isColourName(name) ? "COLOUR" : "TEXT", values: [] }]);
  };

  const removeAxis = (index: number) => {
    if (rows.some((r) => r.id) && !confirm("Removing an option merges existing variants and drops the extra ones. Continue?")) {
      return;
    }
    update(axes.filter((_, i) => i !== index));
  };

  const addValue = (index: number) => {
    const raw = (draftValue[index] ?? "").trim();
    if (!raw) return;
    const labels = raw.split(",").map((s) => s.trim()).filter(Boolean);
    const axis = axes[index];
    const fresh = labels.filter((l, i) => labels.indexOf(l) === i && !axis.values.some((v) => v.label.toLowerCase() === l.toLowerCase()));
    if (axis.values.length + fresh.length > 20) return toast.error("At most 20 values per option");
    setDraftValue({ ...draftValue, [index]: "" });
    if (fresh.length) {
      setAxis(index, { values: [...axis.values, ...fresh.map((label) => ({ label, hex: DEFAULT_HEX, images: [] }))] });
    }
  };

  const onValueKey = (index: number) => (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      addValue(index);
    }
  };

  const patchValue = (axisIndex: number, label: string, patch: Partial<ValueDraft>) =>
    setAxes(axes.map((a, i) => (i === axisIndex ? { ...a, values: a.values.map((v) => (v.label === label ? { ...v, ...patch } : v)) } : a)));

  const removeValue = (axisIndex: number, label: string) =>
    setAxis(axisIndex, { values: axes[axisIndex].values.filter((v) => v.label !== label) });

  const toggleImage = (axisIndex: number, label: string, image: string) => {
    const value = axes[axisIndex].values.find((v) => v.label === label);
    if (!value) return;
    const images = value.images.includes(image) ? value.images.filter((i) => i !== image) : [...value.images, image];
    patchValue(axisIndex, label, { images });
  };

  const patchRow = (index: number, patch: Partial<RowDraft>) =>
    setRows(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));

  const commitStock = async (index: number) => {
    const row = rows[index];
    if (!row.id || row.savedStock === undefined) return;
    const value = Number(row.stock);
    if (!Number.isInteger(value) || value < 0 || value > 1_000_000) {
      patchRow(index, { stock: String(row.savedStock) });
      return toast.error("Stock must be a whole number from 0 to 1,000,000");
    }
    if (value === row.savedStock) return;
    const res = await adjustStock({ productId: product._id, variantId: row.id, set: value });
    if ("error" in res) {
      patchRow(index, { stock: String(row.savedStock) });
      return toast.error(errorMessage(res.error));
    }
    const saved = res.data.product.variants?.items.find((i) => i.id === row.id);
    setRows((current) =>
      current.map((r) => (r.id === row.id ? { ...r, stock: String(saved?.stock ?? value), savedStock: saved?.stock ?? value } : r))
    );
    toast.success("Stock updated");
  };

  const validate = (): string | null => {
    if (axes.length === 0) return null;
    const names = axes.map((a) => a.name.trim().toLowerCase());
    if (names.some((n) => !n)) return "Every option needs a name";
    if (new Set(names).size !== names.length) return "Option names must be different";
    if (axes.some((a) => a.values.length === 0)) return "Every option needs at least one value";
    if (rows.length > MAX_VARIANTS) return `At most ${MAX_VARIANTS} variants (this makes ${rows.length})`;
    for (const r of rows) {
      const label = Object.values(r.options).join(" / ");
      if (!(Number(r.price) > 0)) return `Set a price for ${label}`;
      if (r.mrp !== "" && Number(r.mrp) < Number(r.price)) return `MRP of ${label} is below its price`;
      if (!/^\d+$/.test(r.stock)) return `Stock of ${label} must be a whole number`;
    }
    return null;
  };

  const save = async () => {
    const problem = validate();
    if (problem) return toast.error(problem);
    const body: VariantsRequest = {
      axes: axes.map((a) => ({
        name: a.name.trim(),
        type: a.type === "COLOUR" ? "colour" : "text",
        values: a.values.map((v) => ({
          label: v.label,
          ...(a.type === "COLOUR" ? { hex: v.hex } : {}),
          images: v.images,
        })),
      })),
      variants: rows.map((r) => ({
        id: r.id,
        sku: r.sku.trim() || undefined,
        options: axes.map((a) => ({ name: a.name.trim(), value: r.options[a.name] })),
        price: Number(r.price),
        mrp: r.mrp === "" ? undefined : Number(r.mrp),
        stock: Number(r.stock),
        status: r.status,
      })),
    };
    const res = await saveVariants({ productId: product._id, body });
    if ("error" in res) return toast.error(errorMessage(res.error));
    const next = fromProduct(res.data.product);
    setAxes(next.axes);
    setRows(next.rows);
    toast.success(res.data.message);
  };

  const removeAll = async () => {
    if (!confirm("Remove all variants? The product becomes a simple product at its current price and stock.")) return;
    const res = await saveVariants({ productId: product._id, body: { axes: [], variants: [] } });
    if ("error" in res) return toast.error(errorMessage(res.error));
    setAxes([]);
    setRows([]);
    toast.success(res.data.message);
  };

  return (
    <section className="card card--scroll variant-editor">
      <p className="variant-editor__intro">
        Sell one product in several sizes, colours or other options. Each combination has its own SKU, price and stock; the
        product page shows the lowest price and the total stock.
      </p>

      {axes.map((axis, ai) => (
        <fieldset key={ai} className="variant-axis">
          <div className="variant-axis__head">
            <label>
              Option name
              <input
                list="variant-presets"
                value={axis.name}
                onChange={(e) =>
                  setAxis(ai, {
                    name: e.target.value,
                    ...(isColourName(e.target.value) ? { type: "COLOUR" as const } : {}),
                  })
                }
                maxLength={30}
                placeholder="e.g. Size"
              />
            </label>
            <label>
              Shown as
              <select value={axis.type} onChange={(e) => setAxis(ai, { type: e.target.value as "TEXT" | "COLOUR" })}>
                <option value="TEXT">Buttons</option>
                <option value="COLOUR">Colour swatches</option>
              </select>
            </label>
            <button className="btn" type="button" onClick={() => removeAxis(ai)}>
              Remove option
            </button>
          </div>

          <ul className="variant-values">
            {axis.values.map((v) => (
              <li key={v.label}>
                {axis.type === "COLOUR" && (
                  <input
                    type="color"
                    value={v.hex}
                    onChange={(e) => patchValue(ai, v.label, { hex: e.target.value })}
                    aria-label={`Colour of ${v.label}`}
                  />
                )}
                <span>{v.label}</span>
                {axis.type === "COLOUR" && gallery.length > 0 && (
                  <button
                    type="button"
                    className="variant-values__img"
                    onClick={() => setImagePicker(imagePicker === `${ai}:${v.label}` ? "" : `${ai}:${v.label}`)}
                  >
                    {v.images.length} img
                  </button>
                )}
                <button type="button" onClick={() => removeValue(ai, v.label)} aria-label={`Remove ${v.label}`}>
                  <LuX />
                </button>
              </li>
            ))}
            <li className="variant-values__add">
              <input
                value={draftValue[ai] ?? ""}
                onChange={(e) => setDraftValue({ ...draftValue, [ai]: e.target.value })}
                onKeyDown={onValueKey(ai)}
                onBlur={() => addValue(ai)}
                placeholder="Add values, e.g. S, M, L"
                maxLength={120}
              />
            </li>
          </ul>

          {axis.type === "COLOUR" &&
            axis.values.map(
              (v) =>
                imagePicker === `${ai}:${v.label}` && (
                  <div key={v.label} className="variant-gallery">
                    <small>Images shown when “{v.label}” is picked</small>
                    <div>
                      {gallery.map((img) => (
                        <button
                          key={img}
                          type="button"
                          className={v.images.includes(img) ? "is-on" : ""}
                          onClick={() => toggleImage(ai, v.label, img)}
                          aria-pressed={v.images.includes(img)}
                        >
                          <img src={transformImage(img)} alt="" />
                        </button>
                      ))}
                    </div>
                  </div>
                )
            )}
        </fieldset>
      ))}

      <datalist id="variant-presets">
        {PRESETS.map((p) => (
          <option key={p} value={p} />
        ))}
      </datalist>

      <div className="variant-editor__add">
        <button className="btn" type="button" onClick={() => addAxis()} disabled={axes.length >= MAX_AXES}>
          <LuPlus /> Add option
        </button>
        {axes.length === 0 &&
          PRESETS.slice(0, 3).map((p) => (
            <button key={p} className="btn" type="button" onClick={() => addAxis(p)}>
              {p}
            </button>
          ))}
      </div>

      {rows.length > 0 && (
        <div className="variant-table">
          <table>
            <thead>
              <tr>
                <th>Variant</th>
                <th>SKU</th>
                <th>Price (₹)</th>
                <th>MRP (₹)</th>
                <th>Stock</th>
                <th>Status</th>
                <th title="Units sold across all orders">Sold</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={comboKey(axes, r.options)} className={r.status === "DISABLED" ? "is-disabled" : ""}>
                  <td>{axes.map((a) => r.options[a.name]).join(" / ")}</td>
                  <td>
                    <input
                      value={r.sku}
                      onChange={(e) => patchRow(i, { sku: e.target.value })}
                      placeholder="auto"
                      maxLength={60}
                      aria-label="SKU"
                    />
                  </td>
                  <td>
                    <input type="number" min={1} step="0.01" value={r.price} onChange={(e) => patchRow(i, { price: e.target.value })} aria-label="Price" />
                  </td>
                  <td>
                    <input type="number" min={0} step="0.01" value={r.mrp} onChange={(e) => patchRow(i, { mrp: e.target.value })} aria-label="MRP" />
                  </td>
                  <td className={r.status === "ACTIVE" && Number(r.stock) <= lowAt ? "is-low" : ""}>
                    <input
                      type="number"
                      min={0}
                      step={1}
                      value={r.stock}
                      onChange={(e) => patchRow(i, { stock: e.target.value })}
                      onBlur={() => commitStock(i)}
                      onKeyDown={(e) => e.key === "Enter" && (e.target as HTMLInputElement).blur()}
                      aria-label="Stock"
                    />
                  </td>
                  <td>
                    <select value={r.status} onChange={(e) => patchRow(i, { status: e.target.value as RowDraft["status"] })} aria-label="Status">
                      <option value="ACTIVE">Active</option>
                      <option value="DISABLED">Disabled</option>
                    </select>
                  </td>
                  <td
                    className="variant-table__sold"
                    title={r.id && sales.get(r.id) ? `${formatINR(sales.get(r.id)!.revenue)} from ${sales.get(r.id)!.orders} order(s)` : undefined}
                  >
                    {r.id ? sales.get(r.id)?.units ?? 0 : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {rows.some((r) => r.id) && (
            <p className="variant-table__hint">Stock of saved variants saves as soon as you leave the field.</p>
          )}
        </div>
      )}

      <footer className="variant-editor__footer">
        <span>
          {rows.length > 0
            ? `${rows.length} variant${rows.length === 1 ? "" : "s"} · ${totalStock} in stock${
                activePrices.length ? ` · from ${formatINR(Math.min(...activePrices))}` : ""
              }`
            : "No variants yet"}
        </span>
        <div>
          {(product.variants?.items.length ?? 0) > 0 && (
            <button className="btn btn--danger" type="button" onClick={removeAll} disabled={saving}>
              Remove all variants
            </button>
          )}
          <button className="btn btn--primary" type="button" onClick={save} disabled={saving}>
            {saving ? "Saving…" : "Save variants"}
          </button>
        </div>
      </footer>
    </section>
  );
};

export default VariantEditor;
