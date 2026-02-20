import { ChangeEvent, FormEvent, useState } from "react";
import AdminSidebar from "../../../components/admin/AdminSidebar";
import { useNewProductMutation } from "../../../redux/api/productAPI";
import { useSelector } from "react-redux";
import { RootState } from "../../../redux/store";
import toast from "react-hot-toast";
import { useNavigate } from "react-router-dom";
import { FetchBaseQueryError } from "@reduxjs/toolkit/query/react";
import { MessageResponse } from "../../../types/api-types";

const NewProduct = () => {
  const { user } = useSelector((state: RootState) => state.userReducer);
  const navigate = useNavigate();
  
  const [name, setName] = useState<string>("");
  const [category, setCategory] = useState<string>("");
  const [price, setPrice] = useState<number>(1000);
  const [stock, setStock] = useState<number>(1);
  const [photoPrev, setPhotoPrev] = useState<string>("");
  const [photo, setPhoto] = useState<File>();
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const [newProduct] = useNewProductMutation();

  const changeImageHandler = (e: ChangeEvent<HTMLInputElement>) => {
    const file: File | undefined = e.target.files?.[0];

    const reader: FileReader = new FileReader();

    if (file) {
      reader.readAsDataURL(file);
      reader.onloadend = () => {
        if (typeof reader.result === "string") {
          setPhotoPrev(reader.result);
          setPhoto(file);
        }
      };
    }
  };

  const submitHandler = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setIsLoading(true);

    try {
      if (!name || !price || !stock || !category || !photo) {
        toast.error("Please fill all fields and add a photo");
        setIsLoading(false);
        return;
      }

      if (!user?._id) {
        toast.error("Please login as admin first");
        setIsLoading(false);
        return;
      }

      const formData = new FormData();
      formData.set("name", name);
      formData.set("price", price.toString());
      formData.set("stock", stock.toString());
      formData.set("category", category);
      formData.set("photo", photo);

      const res = await newProduct({ formData, id: user._id });

      if ("data" in res && res.data) {
        toast.success(res.data.message);
        navigate("/admin/product");
      } else {
        const error = res.error as FetchBaseQueryError;
        const message = (error.data as MessageResponse).message;
        toast.error(message);
      }
    } catch (error) {
      toast.error("Something went wrong");
    }
    setIsLoading(false);
  };


  return (
    <div className="admin-container" style={{ display: 'grid', gridTemplateColumns: 'min-content 1fr', height: 'calc(100vh - 4rem)', backgroundColor: '#f3f4f6', overflow: 'hidden' }}>
      <AdminSidebar />
      <main className="product-management" style={{ padding: '1rem', display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <article style={{
            backgroundColor: 'white',
            padding: '2rem',
            borderRadius: '12px',
            boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
            width: '100%',
            maxWidth: '900px',
            height: '80vh',
            display: 'flex',
            flexDirection: 'column'
        }}>
          {/* Header Actions */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
              <h2 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#1f2937', margin: 0 }}>Create New Product</h2>
              <button onClick={(e: any) => submitHandler(e)} disabled={isLoading} style={{
                    padding: '0.5rem 1.5rem',
                    backgroundColor: '#3b82f6',
                    color: 'white',
                    border: 'none',
                    borderRadius: '6px',
                    fontWeight: '600',
                    cursor: isLoading ? 'not-allowed' : 'pointer',
                    transition: 'background 0.2s',
                    opacity: isLoading ? 0.7 : 1,
                    fontSize: '0.9rem'
                }}>
                  {isLoading ? "Creating..." : "Create"}
              </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: photoPrev ? '1fr 1fr' : '1fr', gap: '2rem', flex: 1, overflowY: 'auto', alignItems: 'start' }}>
               {photoPrev && (
                   <div style={{ textAlign: 'center', backgroundColor: '#f9fafb', padding: '2rem', borderRadius: '8px', height: '100%', maxHeight: '400px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                        <img src={photoPrev} alt="New Image" style={{ maxHeight: '100%', maxWidth: '100%', objectFit: 'contain' }} />
                   </div>
               )}

              <form style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', width: '100%' }}>
                
                <div>
                  <label style={{ display: 'block', marginBottom: '0.5rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Name</label>
                  <input
                    required
                    type="text"
                    placeholder="Product Name"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    style={{ width: '100%', padding: '0.75rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none', fontSize: '0.9rem' }}
                  />
                </div>
                
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                    <div>
                    <label style={{ display: 'block', marginBottom: '0.5rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Price</label>
                    <input
                        required
                        type="number"
                        placeholder="Price"
                        value={price}
                        onChange={(e) => setPrice(Number(e.target.value))}
                        style={{ width: '100%', padding: '0.75rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none', fontSize: '0.9rem' }}
                    />
                    </div>
                    <div>
                    <label style={{ display: 'block', marginBottom: '0.5rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Stock</label>
                    <input
                        required
                        type="number"
                        placeholder="Stock"
                        value={stock}
                        onChange={(e) => setStock(Number(e.target.value))}
                        style={{ width: '100%', padding: '0.75rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none', fontSize: '0.9rem' }}
                    />
                    </div>
                </div>

                <div>
                  <label style={{ display: 'block', marginBottom: '0.5rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Category</label>
                  <input
                    required
                    type="text"
                    placeholder="Category"
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    style={{ width: '100%', padding: '0.75rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none', fontSize: '0.9rem' }}
                  />
                </div>

                <div>
                  <label style={{ display: 'block', marginBottom: '0.5rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Photo</label>
                  <input required type="file" onChange={changeImageHandler} style={{ width: '100%', padding: '0.25rem', fontSize: '0.85rem' }} />
                </div>
              </form>
          </div>
        </article>
      </main>
    </div>
  );
};

export default NewProduct;
