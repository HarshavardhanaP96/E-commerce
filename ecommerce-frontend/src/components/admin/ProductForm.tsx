import { ChangeEvent, FormEvent, useEffect, useState } from "react";
import { transformImage } from "../../utils/features";
import { useCategoryTreeQuery } from "../../redux/api/categoryAPI";
import { MAX_GALLERY, ProductFormValues } from "./productFormData";

type Props = {
  values: ProductFormValues;
  onChange: (values: ProductFormValues) => void;
  onSubmit: (e: FormEvent) => void;
  currentPhoto?: string;
  /** The gallery already saved on the product. */
  currentImages?: string[];
  /** Price and stock come from the variants and cannot be edited here. */
  derived?: boolean;
};

const MAX_BYTES = 5 * 1024 * 1024;

/** Two-column product editor: preview card on the left, fields on the right. Fits without scrolling. */
const ProductForm = ({ values, onChange, onSubmit, currentPhoto, currentImages = [], derived }: Props) => {
  const [preview, setPreview] = useState<string>("");
  const [galleryPreviews, setGalleryPreviews] = useState<string[]>([]);
  const { data: categoryData } = useCategoryTreeQuery();
  const categories = categoryData?.categories ?? [];

  useEffect(() => {
    if (!values.photo) return setPreview("");
    const url = URL.createObjectURL(values.photo);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [values.photo]);

  useEffect(() => {
    const urls = values.images.map((file) => URL.createObjectURL(file));
    setGalleryPreviews(urls);
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, [values.images]);

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

  const onGallery = (e: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    if (files.length > MAX_GALLERY) {
      e.target.value = "";
      return alert(`You can add up to ${MAX_GALLERY} gallery pictures`);
    }
    if (files.some((f) => f.size > MAX_BYTES)) {
      e.target.value = "";
      return alert("Each image must be 5 MB or smaller");
    }
    onChange({ ...values, images: files });
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
          <input
            type="number"
            min={1}
            step="0.01"
            value={values.price}
            onChange={set("price")}
            required={!currentPhoto}
            disabled={derived}
            title={derived ? "Lowest variant price. Edit prices on the Variants tab." : undefined}
          />
        </label>
        <label>
          Stock
          <input
            type="number"
            min={0}
            step={1}
            value={values.stock}
            onChange={set("stock")}
            required={!currentPhoto}
            disabled={derived}
            title={derived ? "Total of all variants. Edit stock on the Variants tab." : undefined}
          />
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
          Linked category (optional)
          <select value={values.categoryId} onChange={set("categoryId")}>
            <option value="">{values.categoryId ? "None" : "Not linked"}</option>
            {categories.map((c) => (
              <option key={c._id} value={c._id}>{c.name}</option>
            ))}
          </select>
        </label>
        <label className="span-2">
          Country of origin
          <input value={values.countryOfOrigin} onChange={set("countryOfOrigin")} maxLength={56} placeholder="e.g. India" />
        </label>
        <label className="span-2">
          Photo (JPEG, PNG, WEBP or GIF, max 5 MB)
          <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" onChange={onPhoto} required={!currentPhoto} />
        </label>
        <fieldset className="span-2 gallery">
          <legend>Gallery (up to {MAX_GALLERY} more pictures, max 5 MB each)</legend>
          <input type="file" multiple accept="image/jpeg,image/png,image/webp,image/gif" onChange={onGallery}
                 aria-label="Gallery pictures" />
          {galleryPreviews.length > 0 ? (
            <>
              <ul className="gallery__thumbs">
                {galleryPreviews.map((url, i) => (
                  <li key={url}><img src={url} alt={`New gallery picture ${i + 1}`} /></li>
                ))}
              </ul>
              {currentImages.length > 0 && <small>These replace the {currentImages.length} saved picture(s) when you save.</small>}
            </>
          ) : currentImages.length > 0 ? (
            <ul className="gallery__thumbs">
              {currentImages.map((url, i) => (
                <li key={url}><img src={transformImage(url)} alt={`Gallery picture ${i + 1}`} /></li>
              ))}
            </ul>
          ) : (
            <small>No gallery pictures yet.</small>
          )}
          <small>Choosing new pictures replaces the whole gallery and any images set on colours.</small>
        </fieldset>
      </form>
    </div>
  );
};

export default ProductForm;
