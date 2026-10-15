export type ProductFormValues = {
  name: string;
  price: string;
  stock: string;
  category: string;
  photo?: File;
};

export const toFormData = (v: ProductFormValues) => {
  const fd = new FormData();
  if (v.name) fd.set("name", v.name);
  if (v.price !== "") fd.set("price", v.price);
  if (v.stock !== "") fd.set("stock", v.stock);
  if (v.category) fd.set("category", v.category);
  if (v.photo) fd.set("photo", v.photo);
  return fd;
};
