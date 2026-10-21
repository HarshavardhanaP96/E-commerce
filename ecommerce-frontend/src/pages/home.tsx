import { brand } from "../tenant/tenant";
import { CSSProperties, useEffect } from "react";
import { Link } from "react-router-dom";
import { LuArrowRight, LuPackageOpen } from "react-icons/lu";
import ProductCard from "../components/productCard";
import { useCategoriesQuery, useLatestProductsQuery } from "../redux/api/productAPI";
import toast from "react-hot-toast";
import { CartItem } from "../types/types";
import { useDispatch } from "react-redux";
import { addToCart } from "../redux/reducer/cartReducer";
import { transformImage } from "../utils/features";
import { fillSpan } from "../utils/grid";

const Home = () => {
  const { data, isLoading, isError } = useLatestProductsQuery();
  const { data: categories } = useCategoriesQuery();

  const dispatch = useDispatch();

  const addToCartHandler = (cartItem: CartItem) => {
    if (cartItem.stock < 1) return toast.error("Out of stock");
    dispatch(addToCart(cartItem));
    toast.success("Added to cart");
  };

  useEffect(() => {
    if (isError) toast.error("Cannot fetch the products");
  }, [isError]);

  const products = data?.products ?? [];
  const shelf = products.filter((p) => p.photo).slice(0, 3);
  // The home grid has fixed 2/3/4 columns, so the "View all" tile can fill exactly what the last row leaves.
  const spans = {
    "--span-2": fillSpan(products.length, 2),
    "--span-3": fillSpan(products.length, 3),
    "--span-4": fillSpan(products.length, 4),
  } as CSSProperties;

  return (
    <div className="home">
      <section className="hero" aria-labelledby="hero-title">
        <div className="hero__copy">
          <h1 id="hero-title">{brand.name}</h1>
          <p>New products every week, picked for everyday use.</p>
          <Link to="/search" className="hero__cta">
            Shop all products <LuArrowRight aria-hidden />
          </Link>
        </div>
        <div className="hero__shelf" aria-hidden>
          {shelf.map((p) => (
            <img key={p._id} src={transformImage(p.photo)} alt="" />
          ))}
        </div>
      </section>

      {categories && categories.categories.length > 0 && (
        <nav className="category-chips" aria-label="Shop by category">
          {categories.categories.map((c) => (
            <Link key={c} className="chip" to={`/search?category=${encodeURIComponent(c)}`}>
              {c.charAt(0).toUpperCase() + c.slice(1)}
            </Link>
          ))}
        </nav>
      )}

      <header className="section-head">
        <h2>Latest products</h2>
      </header>

      <section className="product-grid product-grid--home" style={spans} aria-label="Latest products">
        {isLoading ? (
          Array.from({ length: 4 }, (_, i) => <div key={i} className="product-card product-card--skeleton" />)
        ) : products.length ? (
          <>
            {products.map((i) => (
              <ProductCard key={i._id} product={i} handler={addToCartHandler} />
            ))}
            <Link to="/search" className="view-all-tile">
              <strong>Browse all products</strong>
              <span>
                Search, filter and sort everything in the shop <LuArrowRight aria-hidden />
              </span>
            </Link>
          </>
        ) : (
          <div className="empty-state">
            <LuPackageOpen />
            <p>No products yet. Check back soon.</p>
          </div>
        )}
      </section>
    </div>
  );
};

export default Home;
