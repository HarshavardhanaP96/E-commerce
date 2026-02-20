import { Link } from "react-router-dom";
import ProductCard from "../components/productCard";
import { useLatestProductsQuery } from "../redux/api/productAPI";
import toast from "react-hot-toast";
import { CartItem } from "../types/types";
import { useDispatch } from "react-redux";
import { addToCart } from "../redux/reducer/cartReducer";

const Home = () => {
  const { data, isLoading, isError } = useLatestProductsQuery("");

  const dispatch = useDispatch();

  const addToCartHandler = (cartItem: CartItem) => {
    if (cartItem.stock < 1) return toast.error("Out of Stock");
    dispatch(addToCart(cartItem));
    toast.success("Added to Cart");
  };

  if (isError) toast.error("Cannot Fetch the Products");

  return (
    <div className="home" style={{ fontFamily: '"Inter", sans-serif', backgroundColor: '#f9fafb', minHeight: '100vh' }}>
      
      {/* Hero Section */}
      <section style={{
        position: 'relative',
        height: '400px',
        width: '100%',
        backgroundImage: 'url(https://images.unsplash.com/photo-1441986300917-64674bd600d8?ixlib=rb-1.2.1&auto=format&fit=crop&w=1950&q=80)',
        backgroundSize: 'cover',
        backgroundPosition: 'center',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: 'white',
        marginBottom: '3rem'
      }}>
        <div style={{
            position: 'absolute',
            inset: 0,
            backgroundColor: 'rgba(0,0,0,0.5)'
        }}></div>
        <div style={{ position: 'relative', textAlign: 'center', maxWidth: '800px', padding: '1rem' }}>
            <h1 style={{ fontSize: '3rem', fontWeight: '800', marginBottom: '1rem', letterSpacing: '-0.025em' }}>Welcome to 6Pack Store</h1>
            <p style={{ fontSize: '1.25rem', opacity: 0.9 }}>Discover the latest trends in tech and fashion. Quality products, best prices.</p>
        </div>
      </section>

      {/* Latest Products Header */}
      <div style={{ maxWidth: '1200px', margin: '0 auto', padding: '0 2rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2rem' }}>
        <h1 style={{ fontSize: '1.875rem', fontWeight: '700', color: '#111827' }}>
            Latest Products
        </h1>
        <Link to="/search" className="findmore" style={{ 
            fontSize: '1rem', 
            fontWeight: '600', 
            color: '#3b82f6', 
            textDecoration: 'none',
            display: 'flex',
            alignItems: 'center',
            gap: '0.5rem',
            padding: '0.5rem 1rem',
            borderRadius: '8px',
            backgroundColor: 'rgba(59, 130, 246, 0.1)'
        }}>
          View More
        </Link>
      </div>

      <main style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
          gap: '2rem',
          maxWidth: '1200px',
          margin: '0 auto',
          padding: '0 2rem 4rem 2rem'
      }}>
        {isLoading ? (
          <div style={{ width: '100%', height: '50vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
             <h2 style={{ color: '#6b7280' }}>Loading Products...</h2>
          </div>
        ) : (
          data?.products.map((i) => (
            <ProductCard
              key={i._id}
              productId={i._id}
              name={i.name}
              price={i.price}
              stock={i.stock}
              handler={addToCartHandler}
              photo={i.photo}
            />
          ))
        )}
      </main>
    </div>
  );
};

export default Home;
