import { ChangeEvent, FormEvent, useEffect, useState } from "react";
import { transformImage } from "../../utils/features";
import { ProductFormValues } from "./productFormData";

type Props = {
  values: ProductFormValues;
  onChange: (values: ProductFormValues) => void;
  onSubmit: (e: FormEvent) => void;
  currentPhoto?: string;
};

const MAX_BYTES = 5 * 1024 * 1024;

/** Two-column product editor: preview card on the left, fields on the right. Fits without scrolling. */
const ProductForm = ({ values, onChange, onSubmit, currentPhoto }: Props) => {
  const [preview, setPreview] = useState<string>("");

  useEffect(() => {
    if (!values.photo) return setPreview("");
    const url = URL.createObjectURL(values.photo);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [values.photo]);

  const set =
    (field: keyof ProductFormValues) =>
    (e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    onChange({ ...values, [field]: e.target.value });

  const onPhoto = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && file.size > MAX_BYTES) {
      e.target.value = "";
      return alert("Image must be 5 MB or smaller");
    }
    onChange({ ...values, photo: file });
  };

  const image = preview || transformImage(currentPhoto);

  return (
    <div className="detail-grid">
      <section className="card card--center">
        {image ? <img className="detail-photo" src={image} alt="Product" /> : <div className="detail-photo placeholder">No photo</div>}
        <h3>{values.name || "Product name"}</h3>
        <strong className="detail-price">{values.price ? `₹${Number(values.price).toLocaleString("en-IN")}` : "₹—"}</strong>
        {values.stock !== "" && (
          <span className={`badge ${Number(values.stock) > 0 ? "badge--delivered" : "badge--processing"}`}>
            {Number(values.stock) > 0 ? `${values.stock} in stock` : "Out of stock"}
          </span>
        )}
      </section>

      <form id="product-form" className="card form-grid" onSubmit={onSubmit}>
        <label>
          Name
          <input value={values.name} onChange={set("name")} maxLength={120} required={!currentPhoto} />
        </label>
        <label>
          Category
          <input value={values.category} onChange={set("category")} placeholder="e.g. laptop, camera" maxLength={50} required={!currentPhoto} />
        </label>
        <label>
          Price (₹)
          <input type="number" min={1} step="0.01" value={values.price} onChange={set("price")} required={!currentPhoto} />
        </label>
        <label>
          Stock
          <input type="number" min={0} step={1} value={values.stock} onChange={set("stock")} required={!currentPhoto} />
        </label>
        <label>
          SKU
          <input value={values.sku} onChange={set("sku")} maxLength={60} />
        </label>
        <label>
          Brand
          <input value={values.brand} onChange={set("brand")} maxLength={60} />
        </label>
        <label>
          MRP (₹)
          <input type="number" min={0} step="0.01" value={values.mrp} onChange={set("mrp")} />
        </label>
        <label>
          Status
          <select value={values.status} onChange={set("status")}>
            <option value="ACTIVE">Active</option>
            <option value="DRAFT">Draft (hidden)</option>
            <option value="ARCHIVED">Archived (hidden)</option>
          </select>
        </label>
        <label className="span-2">
          Tags (comma separated)
          <input value={values.tags} onChange={set("tags")} placeholder="e.g. summer, sale" />
        </label>
        <label className="span-2">
          Description
          <textarea value={values.description} onChange={set("description")} rows={3} maxLength={5000} />
        </label>
        <label className="span-2">
          Photo (JPEG, PNG, WEBP or GIF, max 5 MB)
          <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" onChange={onPhoto} required={!currentPhoto} />
        </label>
      </form>
    </div>
  );
};

export default ProductForm;
