import { ChangeEvent, FormEvent, useState } from "react";
import { FaTrash } from "react-icons/fa";
import AdminSidebar from "../../../components/admin/AdminSidebar";
// import { useSelector } from "react-redux";
// import { UserReducerInitialState } from "../../../types/reducer-types";
// import { Navigate } from "react-router-dom";
// import { useDeleteProductMutation, useProductDetailsQuery, useUpdateProductMutation } from "../../../redux/api/productAPI";
// import { server } from "../../../redux/store";
// import toast from "react-hot-toast";

const img =
  "https://images.unsplash.com/photo-1542291026-7eec264c27ff?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxzZWFyY2h8Mnx8c2hvZXN8ZW58MHx8MHx8&w=1000&q=804";

const Productmanagement = () => {
  const [price] = useState<number>(2000);
  const [stock] = useState<number>(10);
  const [name] = useState<string>("Puma Shoes");
  const [photo] = useState<string>(img);
  const [category] = useState<string>("footwear");

  const [priceUpdate, setPriceUpdate] = useState<number>(price);
  const [stockUpdate, setStockUpdate] = useState<number>(stock);
  const [nameUpdate, setNameUpdate] = useState<string>(name);
  const [categoryUpdate, setCategoryUpdate] = useState<string>(category);
  const [photoUpdate, setPhotoUpdate] = useState<string>(photo);
  // const [photoFile, setPhotoFile] = useState<File>();

  const changeImageHandler = (e: ChangeEvent<HTMLInputElement>) => {
    const file: File | undefined = e.target.files?.[0];

    const reader: FileReader = new FileReader();

    if (file) {
      reader.readAsDataURL(file);
      reader.onloadend = () => {
        if (typeof reader.result === "string") {
          setPhotoUpdate(reader.result);
        }
      };
    }
  };

  const submitHandler = (e: FormEvent<HTMLFormElement>): void => {
    e.preventDefault();
    // setName(nameUpdate);
    // setPrice(priceUpdate);
    // setStock(stockUpdate);
    // setPhoto(photoUpdate);
    console.log("Mock update");
  };

  return (
    <div className="admin-container" style={{ display: 'grid', gridTemplateColumns: 'min-content 1fr', height: 'calc(100vh - 4rem)', backgroundColor: '#f3f4f6' }}>
      <AdminSidebar />
      <main className="product-management" style={{ padding: '2rem', display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
        <div style={{ width: '100%', maxWidth: '1000px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '2rem', height: '80vh' }}>
            
            {/* Product Status & Image Card */}
            <section style={{
                backgroundColor: 'white',
                padding: '2rem',
                borderRadius: '12px',
                boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                display: 'flex',
                flexDirection: 'column',
                gap: '1rem',
                alignItems: 'center',
                justifyContent: 'center',
                textAlign: 'center',
                position: 'relative',
                height: '100%'
            }}>
                <div style={{ 
                    position: 'absolute', 
                    top: '20px', 
                    right: '20px', 
                    backgroundColor: stock > 0 ? '#dcfce7' : '#fee2e2', 
                    color: stock > 0 ? '#166534' : '#991b1b',
                    padding: '0.25rem 0.75rem',
                    borderRadius: '9999px',
                    fontSize: '0.875rem',
                    fontWeight: '600'
                }}>
                    {stock > 0 ? `${stock} Available` : 'Out of Stock'}
                </div>

                <strong style={{ position: 'absolute', top: '20px', left: '20px', color: '#6b7280', fontSize: '0.875rem' }}>ID: sdfsdfsggfgdf</strong>
                
                <img src={photo} alt="Product" style={{ 
                    width: '100%', 
                    maxWidth: '250px', 
                    height: '250px', 
                    objectFit: 'contain', 
                    margin: '1rem 0'
                }} />
                
                <h3 style={{ fontSize: '1.25rem', color: '#374151', margin: 0 }}>{name}</h3>
                <h2 style={{ fontSize: '2rem', fontWeight: 'bold', color: '#1f2937' }}>₹{price}</h2>
            </section>

            {/* Update Form Card */}
            <article style={{
                backgroundColor: 'white',
                padding: '2rem',
                borderRadius: '12px',
                boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
                position: 'relative',
                height: '100%',
                display: 'flex',
                flexDirection: 'column'
            }}>
                {/* Header Actions */}
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
                    <h2 style={{ fontSize: '1.25rem', fontWeight: '600', color: '#1f2937', margin: 0 }}>Manage Product</h2>
                    <div style={{ display: 'flex', gap: '1rem' }}>
                        <button onClick={(e: any) => submitHandler(e)} style={{
                            padding: '0.5rem 1rem',
                            backgroundColor: '#3b82f6',
                            color: 'white',
                            border: 'none',
                            borderRadius: '6px',
                            fontWeight: '600',
                            cursor: 'pointer',
                            transition: 'background 0.2s',
                            fontSize: '0.9rem'
                        }}>
                            Update
                        </button>
                        <button className="product-delete-btn" style={{
                            backgroundColor: '#fee2e2',
                            color: '#991b1b',
                            border: 'none',
                            borderRadius: '50%',
                            width: '36px',
                            height: '36px',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            cursor: 'pointer',
                            transition: 'all 0.2s'
                        }}>
                            <FaTrash />
                        </button>
                    </div>
                </div>

                <form style={{ display: 'flex', flexDirection: 'column', gap: '1rem', flex: 1, overflowY: 'auto', paddingRight: '0.5rem' }}>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Name</label>
                        <input
                            type="text"
                            placeholder="Product Name"
                            value={nameUpdate}
                            onChange={(e) => setNameUpdate(e.target.value)}
                            style={{ width: '100%', padding: '0.65rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none' }}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Price</label>
                        <input
                            type="number"
                            placeholder="Price"
                            value={priceUpdate}
                            onChange={(e) => setPriceUpdate(Number(e.target.value))}
                            style={{ width: '100%', padding: '0.65rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none' }}
                        />
                    </div>
                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Stock</label>
                        <input
                            type="number"
                            placeholder="Stock"
                            value={stockUpdate}
                            onChange={(e) => setStockUpdate(Number(e.target.value))}
                            style={{ width: '100%', padding: '0.65rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none' }}
                        />
                    </div>

                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Category</label>
                        <input
                            type="text"
                            placeholder="eg. laptop, camera"
                            value={categoryUpdate}
                            onChange={(e) => setCategoryUpdate(e.target.value)}
                            style={{ width: '100%', padding: '0.65rem', borderRadius: '6px', border: '1px solid #d1d5db', outline: 'none' }}
                        />
                    </div>

                    <div>
                        <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.875rem', fontWeight: '600', color: '#4b5563' }}>Photo</label>
                        <input 
                            type="file" 
                            onChange={changeImageHandler} 
                            style={{ width: '100%', padding: '0.5rem', fontSize: '0.875rem' }}
                        />
                    </div>

                    {photoUpdate && (
                        <div style={{ width: '100%', textAlign: 'center', backgroundColor: '#f9fafb', padding: '0.5rem', borderRadius: '6px' }}>
                            <img src={photoUpdate} alt="New Image" style={{ maxHeight: '80px', objectFit: 'contain' }} />
                        </div>
                    )}
                </form>
            </article>
        </div>
      </main>
    </div>
  );
};

export default Productmanagement;
