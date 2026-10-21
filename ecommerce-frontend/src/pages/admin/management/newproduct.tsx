import { FormEvent, useState } from "react";
import toast from "react-hot-toast";
import { useNavigate } from "react-router-dom";
import DetailLayout from "../../../components/admin/DetailLayout";
import ProductForm from "../../../components/admin/ProductForm";
import { ProductFormValues, emptyProductForm, toFormData } from "../../../components/admin/productFormData";
import { errorMessage } from "../../../redux/api/baseQuery";
import { useNewProductMutation } from "../../../redux/api/productAPI";

const NewProduct = () => {
  const navigate = useNavigate();
  const [values, setValues] = useState<ProductFormValues>(emptyProductForm);
  const [newProduct, { isLoading }] = useNewProductMutation();

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    if (!values.photo) return toast.error("Please add a photo");
    const res = await newProduct(toFormData(values));
    if ("error" in res) return toast.error(errorMessage(res.error));
    toast.success(res.data.message);
    navigate("/admin/product");
  };

  return (
    <DetailLayout
      parent={{ to: "/admin/product", label: "Products" }}
      title="New product"
      actions={
        <button form="product-form" type="submit" className="btn btn--primary" disabled={isLoading}>
          {isLoading ? "Saving…" : "Save"}
        </button>
      }
    >
      <ProductForm values={values} onChange={setValues} onSubmit={submit} />
    </DetailLayout>
  );
};

export default NewProduct;
