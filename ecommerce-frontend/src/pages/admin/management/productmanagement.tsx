import { FormEvent, useEffect, useState } from "react";
import toast from "react-hot-toast";
import { LuTrash2 } from "react-icons/lu";
import { useNavigate, useParams } from "react-router-dom";
import DetailLayout from "../../../components/admin/DetailLayout";
import ProductForm from "../../../components/admin/ProductForm";
import { ProductFormValues, emptyProductForm, toFormData } from "../../../components/admin/productFormData";
import Loader from "../../../components/admin/Loader";
import { errorMessage } from "../../../redux/api/baseQuery";
import {
  useDeleteProductMutation,
  useProductDetailsQuery,
  useUpdateProductMutation,
} from "../../../redux/api/productAPI";

const ProductManagement = () => {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const { data, isLoading, isError } = useProductDetailsQuery(id);
  const [updateProduct, { isLoading: saving }] = useUpdateProductMutation();
  const [deleteProduct, { isLoading: deleting }] = useDeleteProductMutation();
  const [values, setValues] = useState<ProductFormValues>(emptyProductForm);

  const product = data?.product;
  useEffect(() => {
    if (product) {
      setValues({
        ...emptyProductForm,
        name: product.name,
        price: String(product.price),
        stock: String(product.stock),
        category: product.category,
        sku: product.sku ?? "",
        brand: product.brand ?? "",
        description: product.description ?? "",
        mrp: product.mrp != null ? String(product.mrp) : "",
        status: product.status ?? "ACTIVE",
        tags: (product.tags ?? []).join(", "),
      });
    }
  }, [product]);

  useEffect(() => {
    if (isError) {
      toast.error("Product not found");
      navigate("/admin/product", { replace: true });
    }
  }, [isError, navigate]);

  if (isLoading || !product) return <Loader />;

  const save = async (e?: FormEvent) => {
    e?.preventDefault();
    const res = await updateProduct({ productId: id, formData: toFormData(values) });
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    setValues((v) => ({ ...v, photo: undefined }));
  };

  const remove = async () => {
    if (!confirm(`Delete "${product.name}"? This cannot be undone.`)) return;
    const res = await deleteProduct(id);
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    navigate("/admin/product");
  };

  return (
    <DetailLayout
      title={product.name}
      subtitle={`ID ${product._id}`}
      actions={
        <>
          <button form="product-form" type="submit" className="btn btn--primary" disabled={saving}>
            {saving ? "Saving…" : "Save"}
          </button>
          <button className="btn btn--danger" onClick={remove} disabled={deleting}>
            <LuTrash2 /> Delete
          </button>
        </>
      }
    >
      <ProductForm values={values} onChange={setValues} onSubmit={save} currentPhoto={product.photo} />
    </DetailLayout>
  );
};

export default ProductManagement;
