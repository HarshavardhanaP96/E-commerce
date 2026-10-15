import { useEffect } from "react";
import { Link } from "react-router-dom";
import { LuArrowRight, LuPackageOpen } from "react-icons/lu";
import ProductCard from "../components/productCard";
import { useLatestProductsQuery } from "../redux/api/productAPI";
import toast from "react-hot-toast";
import { CartItem } from "../types/types";
import { useDispatch } from "react-redux";
import { addToCart } from "../redux/reducer/cartReducer";
import hero from "../assets/hero.webp";

const Home = () => {
  const { data, isLoading, isError } = useLatestProductsQuery();

  const dispatch = useDispatch();

  const addToCartHandler = (cartItem: CartItem) => {
    if (cartItem.stock < 1) return toast.error("Out of Stock");
    dispatch(addToCart(cartItem));
    toast.success("Added to Cart");
  };

  useEffect(() => {
    if (isError) toast.error("Cannot fetch the products");
  }, [isError]);

  return (
    <div className="home">
      <section className="hero">
        <div className="hero__copy">
          <p className="eyebrow">New arrivals every week</p>
          <h1>Welcome to 6Pack Store</h1>
          <p>Discover the latest trends in tech and fashion. Quality products, best prices.</p>
          <Link to="/search" className="hero__cta">
            Shop all products <LuArrowRight />
          </Link>
        </div>
        <img className="hero__art" src={hero} alt="" aria-hidden />
      </section>

      <header className="section-head">
        <h2>Latest products</h2>
        <Link to="/search" className="link-arrow">
          View all <LuArrowRight />
        </Link>
      </header>

      <main className="product-grid">
        {isLoading ? (
          Array.from({ length: 4 }, (_, i) => <div key={i} className="product-card product-card--skeleton" />)
        ) : data?.products.length ? (
          data.products.map((i) => (
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
        ) : (
          <div className="empty-state">
            <LuPackageOpen />
            <p>No products yet. Check back soon.</p>
          </div>
        )}
      </main>
    </div>
  );
};

export default Home;
