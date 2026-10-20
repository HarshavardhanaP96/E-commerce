import { ChangeEvent, FormEvent, useEffect, useState } from "react";
import { transformImage } from "../../utils/features";
import { useCategoryTreeQuery } from "../../redux/api/categoryAPI";
import { LuImagePlus } from "react-icons/lu";
import { MAX_GALLERY, ProductFormValues, splitPictures } from "./productFormData";
import { currencySymbol, formatMoney } from "../../utils/money";

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

/** Two-column product editor: preview and pictures on the left, fields on the right. */
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

  const onPictures = (e: ChangeEvent<HTMLInputElement>) => {
    const result = splitPictures(Array.from(e.target.files ?? []));
    if ("error" in result) {
      e.target.value = "";
      return alert(result.error);
    }
    onChange({ ...values, ...result });
  };

  const image = preview || transformImage(currentPhoto);
  const pending = !!values.photo;
  // New picks replace the gallery only when they include gallery pictures; the server keeps it otherwise.
  const galleryShown = galleryPreviews.length > 0 ? galleryPreviews : currentImages.map((url) => transformImage(url));
  const thumbs = [
    ...(image ? [{ src: image, main: true }] : []),
    ...galleryShown.map((src) => ({ src, main: false })),
  ];
  const hint = !pending
    ? `JPEG, PNG, WEBP or GIF, max 5 MB each. Pick up to ${MAX_GALLERY + 1}: the first is the main photo, the rest the gallery.`
    : galleryPreviews.length > 0
      ? "Not saved yet. Replaces the main photo, the gallery and any images set on colours."
      : currentImages.length > 0
        ? "Not saved yet. Replaces the main photo; the gallery stays."
        : "Not saved yet.";

  return (
    <div className="detail-grid">
      <section className="card card--center">
        {image ? <img className="detail-photo" src={image} alt="Product" /> : <div className="detail-photo placeholder">No photo</div>}
        <h3>{values.name || "Product name"}</h3>
        <strong className="detail-price">{values.price ? formatMoney(Number(values.price)) : `${currencySymbol()}—`}</strong>
        {values.stock !== "" && (
          <span className={`badge ${Number(values.stock) > 0 ? "badge--delivered" : "badge--processing"}`}>
            {Number(values.stock) > 0 ? `${values.stock} in stock` : "Out of stock"}
          </span>
        )}

        <div className="pictures">
          {thumbs.length > 1 && (
            <ul className="pictures__thumbs">
              {thumbs.map((t, i) => (
                <li key={`${i}-${t.src}`} className={t.main ? "is-main" : undefined}>
                  <img src={t.src} alt={t.main ? "Main photo" : `Gallery picture ${i}`} />
                  {t.main && <span>Main</span>}
                </li>
              ))}
            </ul>
          )}
          <label className="btn pictures__upload">
            <LuImagePlus /> {image ? "Replace pictures" : "Upload pictures"}
            <input
              className="sr-only"
              form="product-form"
              type="file"
              multiple
              accept="image/jpeg,image/png,image/webp,image/gif"
              onChange={onPictures}
              required={!currentPhoto}
            />
          </label>
          <small>{hint}</small>
        </div>
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
          Price ({currencySymbol()})
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
          Low-stock alert at
          <input
            type="number"
            min={0}
            step={1}
            value={values.lowStockThreshold}
            onChange={set("lowStockThreshold")}
            placeholder="Store default"
            title="Warn when stock (of the product or any variant) is at or below this number"
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
          MRP ({currencySymbol()})
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
        <label>
          Country of origin
          <input value={values.countryOfOrigin} onChange={set("countryOfOrigin")} maxLength={56} placeholder="e.g. India" />
        </label>
        <label>
          Linked category (optional)
          <select value={values.categoryId} onChange={set("categoryId")}>
            <option value="">{values.categoryId ? "None" : "Not linked"}</option>
            {categories.map((c) => (
              <option key={c._id} value={c._id}>{c.name}</option>
            ))}
          </select>
        </label>
        <label>
          Tags (comma separated)
          <input value={values.tags} onChange={set("tags")} placeholder="e.g. summer, sale" />
        </label>
        <label className="span-2">
          Description
          <textarea value={values.description} onChange={set("description")} rows={3} maxLength={5000} />
        </label>
      </form>
    </div>
  );
};

export default ProductForm;
