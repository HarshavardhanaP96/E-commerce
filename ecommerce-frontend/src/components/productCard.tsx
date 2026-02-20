import { FaPlus } from "react-icons/fa";
import { CartItem } from "../types/types";
import { transformImage } from "../utils/features";

type ProductsProps = {
  productId: string;
  photo: string;
  name: string;
  price: number;
  stock: number;
  handler: (item: CartItem) => void;
};

const ProductCard = ({
  productId,
  photo,
  name,
  price,
  stock,
  handler,
}: ProductsProps) => {
  return (
    <div className="productCard" style={{
        backgroundColor: 'white',
        borderRadius: '12px',
        overflow: 'hidden',
        boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.05)',
        transition: 'all 0.3s ease',
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        height: '100%'
    }}>
      <div style={{ position: 'relative', width: '100%', paddingTop: '100%', backgroundColor: '#f3f4f6' }}>
        <img 
            src={transformImage(photo)} 
            alt={name} 
            loading="lazy" 
            style={{ 
                position: 'absolute', 
                top: 0, 
                left: 0, 
                width: '100%', 
                height: '100%', 
                objectFit: 'contain',
                padding: '1rem' 
            }}
        />
      </div>

      <div style={{ padding: '1.5rem', flex: 1, display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
        <p style={{ fontSize: '1.1rem', fontWeight: '600', color: '#1f2937', margin: 0 }}>{name}</p>
        <span style={{ fontSize: '1.25rem', fontWeight: 'bold', color: '#374151' }}>₹{price.toLocaleString()}</span>
      </div>

      <div className="product-actions">
        <button
          onClick={() =>
            handler({ productId, photo, name, price, quantity: 1, stock })
          }
          aria-label={`Add ${name} to cart`}
          style={{
              width: '100%',
              padding: '1rem',
              backgroundColor: '#1f2937',
              color: 'white',
              border: 'none',
              fontSize: '1rem',
              fontWeight: '600',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '0.5rem',
              transition: 'background 0.2s'
          }}
        >
          <FaPlus />
          <span>Add to Cart</span>
        </button>
      </div>
    </div>
  );
};

export default ProductCard;
