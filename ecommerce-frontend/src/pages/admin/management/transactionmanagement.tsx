import { useState } from "react";
import { FaTrash } from "react-icons/fa";
import { Link } from "react-router-dom";
import AdminSidebar from "../../../components/admin/AdminSidebar";
import { OrderItem } from "../../../types/types";
import { transformImage } from "../../../utils/features";

const img =
  "https://images.unsplash.com/photo-1542291026-7eec264c27ff?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxzZWFyY2h8Mnx8c2hvZXN8ZW58MHx8MHx8&w=1000&q=804";

const orderItems: OrderItem[] = [
  {
    name: "Puma Shoes",
    photo: img,
    _id: "asdsaasdas",
    quantity: 4,
    price: 2000,
    productId: "asdsaasdas",
  },
];

const TransactionManagement = () => {
  const [order, setOrder] = useState({
    name: "Puma Shoes",
    address: "77 black street",
    city: "Neyword",
    state: "Nevada",
    country: "US",
    pinCode: 242433,
    status: "Processing",
    subtotal: 4000,
    discount: 1200,
    shippingCharges: 0,
    tax: 200,
    total: 4000 + 200 + 0 - 1200,
    orderItems,
  });

  const {
    name,
    address,
    city,
    country,
    state,
    pinCode,
    subtotal,
    shippingCharges,
    tax,
    discount,
    total,
    status,
  } = order;

  const updateHandler = (): void => {
    setOrder((prev) => ({
      ...prev,
      status: "Shipped",
    }));
  };

  const deleteHandler = (): void => {
    console.log("Delete order");
  };

  return (
    <div className="admin-container" style={{ display: 'grid', gridTemplateColumns: 'min-content 1fr', height: 'calc(100vh - 4rem)', backgroundColor: '#f3f4f6' }}>
      <AdminSidebar />
      <main className="product-management" style={{ padding: '2rem', overflowY: 'auto' }}>
        <div style={{ maxWidth: '1200px', margin: '0 auto', display: 'grid', gridTemplateColumns: 'minmax(300px, 1fr) 400px', gap: '2rem', alignItems: 'start' }}>
            
          {/* Order Items Section */}
          <section style={{
              backgroundColor: 'white',
              padding: '2rem',
              borderRadius: '12px',
              boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)'
          }}>
            <h2 style={{ fontSize: '1.5rem', fontWeight: '600', marginBottom: '1.5rem', color: '#1f2937' }}>Order Items</h2>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                {orderItems.map((i) => (
                    <ProductCard
                    key={i._id}
                    name={i.name}
                    photo={transformImage(i.photo)}
                    productId={i.productId}
                    _id={i._id}
                    quantity={i.quantity}
                    price={i.price}
                    />
                ))}
            </div>
          </section>

          {/* Order Info Card */}
          <article className="shipping-info-card" style={{
              backgroundColor: 'white',
              padding: '2rem',
              borderRadius: '12px',
              boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.1)',
              position: 'relative',
              display: 'flex', 
              flexDirection: 'column',
              gap: '1.5rem',
              height: 'fit-content' // Important so it doesn't stretch unnecessarily
          }}>
            {/* Header with Actions */}
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h1 style={{ fontSize: '1.5rem', fontWeight: 'bold', color: '#1f2937', margin: 0 }}>Order Info</h1>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button onClick={updateHandler} style={{
                        padding: '0.4rem 0.8rem',
                        backgroundColor: '#3b82f6',
                        color: 'white',
                        border: 'none',
                        borderRadius: '6px',
                        fontWeight: '600',
                        cursor: 'pointer',
                        fontSize: '0.85rem'
                    }}>
                        Process
                    </button>
                    <button className="product-delete-btn" onClick={deleteHandler} style={{
                        color: '#ef4444',
                        background: '#fee2e2',
                        border: 'none',
                        borderRadius: '6px',
                        padding: '0.4rem 0.6rem',
                        fontSize: '1rem',
                        cursor: 'pointer',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center'
                    }}>
                        <FaTrash />
                    </button> 
                </div>
            </div>

            <div>
                <h5 style={{ fontWeight: '600', color: '#374151', marginBottom: '0.5rem' }}>User Info</h5>
                <p style={{ color: '#4b5563', fontSize: '0.95rem' }}>Name: <span style={{ color: '#111827', fontWeight: '500' }}>{name}</span></p>
                <p style={{ color: '#4b5563', fontSize: '0.95rem' }}>
                    Address: {`${address}, ${city}, ${state}, ${country} ${pinCode}`}
                </p>
            </div>

            <div style={{ borderTop: '1px solid #e5e7eb', paddingTop: '1rem' }}>
                <h5 style={{ fontWeight: '600', color: '#374151', marginBottom: '0.5rem' }}>Amount Info</h5>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#4b5563', fontSize: '0.9rem', marginBottom: '0.25rem' }}>
                    <span>Subtotal:</span> <span>₹{subtotal}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#4b5563', fontSize: '0.9rem', marginBottom: '0.25rem' }}>
                    <span>Shipping:</span> <span>₹{shippingCharges}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#4b5563', fontSize: '0.9rem', marginBottom: '0.25rem' }}>
                    <span>Tax:</span> <span>₹{tax}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#4b5563', fontSize: '0.9rem', marginBottom: '0.25rem' }}>
                    <span>Discount:</span> <span style={{ color: '#ef4444' }}>- ₹{discount}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#111827', fontWeight: 'bold', fontSize: '1.1rem', marginTop: '0.5rem', borderTop: '1px dashed #e5e7eb', paddingTop: '0.5rem' }}>
                    <span>Total:</span> <span>₹{total}</span>
                </div>
            </div>

            <div style={{ borderTop: '1px solid #e5e7eb', paddingTop: '1rem' }}>
                <h5 style={{ fontWeight: '600', color: '#374151', marginBottom: '0.5rem' }}>Status</h5>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span style={{ color: '#4b5563' }}>Current Status:</span>
                    <span
                    className={
                        status === "Delivered"
                        ? "purple"
                        : status === "Shipped"
                        ? "green"
                        : "red"
                    }
                    style={{
                        fontWeight: '600',
                        color: status === "Delivered" ? '#8b5cf6' : status === "Shipped" ? '#10b981' : '#ef4444',
                        backgroundColor: status === "Delivered" ? '#f3e8ff' : status === "Shipped" ? '#dcfce7' : '#fee2e2',
                        padding: '0.25rem 0.75rem',
                        borderRadius: '9999px',
                        fontSize: '0.875rem'
                    }}
                    >
                    {status}
                    </span>
                </div>
            </div>
          </article>
        </div>
      </main>
    </div>
  );
};

const ProductCard = ({
  name,
  photo,
  price,
  quantity,
  productId,
}: OrderItem) => (
  <div className="transaction-product-card" style={{
      display: 'flex',
      alignItems: 'center',
      gap: '1rem',
      padding: '1rem',
      backgroundColor: '#f9fafb',
      borderRadius: '8px',
      border: '1px solid #f3f4f6'
  }}>
    <img src={photo} alt={name} style={{ width: '64px', height: '64px', objectFit: 'cover', borderRadius: '4px' }} />
    <Link to={`/product/${productId}`} style={{ flex: 1, color: '#374151', fontWeight: '500', textDecoration: 'none' }}>{name}</Link>
    <span style={{ fontWeight: '600', color: '#111827' }}>
      ₹{price} <span style={{ color: '#6b7280', fontSize: '0.875rem' }}>x {quantity}</span> = ₹{price * quantity}
    </span>
  </div>
);

export default TransactionManagement;
