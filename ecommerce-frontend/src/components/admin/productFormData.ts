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
  photo?: File;
};

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
  photo: undefined,
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
  if (v.photo) fd.set("photo", v.photo);
  return fd;
};
