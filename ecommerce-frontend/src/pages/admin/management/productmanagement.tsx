import { FormEvent, useEffect, useState } from "react";
import toast from "react-hot-toast";
import { FaTrash } from "react-icons/fa";
import { useNavigate, useParams } from "react-router-dom";
import DetailLayout from "../../../components/admin/DetailLayout";
import ProductForm from "../../../components/admin/ProductForm";
import { ProductFormValues, toFormData } from "../../../components/admin/productFormData";
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
  const [values, setValues] = useState<ProductFormValues>({ name: "", price: "", stock: "", category: "" });

  const product = data?.product;
  useEffect(() => {
    if (product) {
      setValues({
        name: product.name,
        price: String(product.price),
        stock: String(product.stock),
        category: product.category,
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
            <FaTrash /> Delete
          </button>
        </>
      }
    >
      <ProductForm values={values} onChange={setValues} onSubmit={save} currentPhoto={product.photo} />
    </DetailLayout>
  );
};

export default ProductManagement;
