export type ProductFormValues = {
  name: string;
  price: string;
  stock: string;
  category: string;
  sku: string;
  brand: string;
  description: string;
  mrp: string;
  status: string;
  tags: string;
  categoryId: string;
  countryOfOrigin: string;
  /** Blank keeps the current value (the store default when never set). */
  lowStockThreshold: string;
  photo?: File;
  /** New gallery pictures. Sending any replaces the whole gallery. */
  images: File[];
};

export const MAX_GALLERY = 5;

export const emptyProductForm: ProductFormValues = {
  name: "",
  price: "",
  stock: "",
  category: "",
  sku: "",
  brand: "",
  description: "",
  mrp: "",
  status: "ACTIVE",
  tags: "",
  categoryId: "",
  countryOfOrigin: "",
  lowStockThreshold: "",
  photo: undefined,
  images: [],
};

export const toFormData = (v: ProductFormValues) => {
  const fd = new FormData();
  if (v.name) fd.set("name", v.name);
  if (v.price !== "") fd.set("price", v.price);
  if (v.stock !== "") fd.set("stock", v.stock);
  if (v.category) fd.set("category", v.category);
  if (v.sku) fd.set("sku", v.sku);
  if (v.brand) fd.set("brand", v.brand);
  if (v.description) fd.set("description", v.description);
  if (v.mrp !== "") fd.set("mrp", v.mrp);
  if (v.status) fd.set("status", v.status);
  if (v.tags) fd.set("tags", v.tags);
  if (v.categoryId) fd.set("categoryId", v.categoryId);
  if (v.countryOfOrigin) fd.set("countryOfOrigin", v.countryOfOrigin);
  if (v.lowStockThreshold !== "") fd.set("lowStockThreshold", v.lowStockThreshold);
  if (v.photo) fd.set("photo", v.photo);
  v.images.forEach((file) => fd.append("images", file));
  return fd;
};

export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

/** One upload for all pictures: the first becomes the main photo, the rest the gallery. */
export const splitPictures = (
  files: File[],
): { photo: File | undefined; images: File[] } | { error: string } => {
  if (files.length > MAX_GALLERY + 1) return { error: `You can add up to ${MAX_GALLERY + 1} pictures` };
  if (files.some((f) => f.size > MAX_IMAGE_BYTES)) return { error: "Each image must be 5 MB or smaller" };
  const [photo, ...images] = files;
  return { photo, images };
};
