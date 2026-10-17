import { useEffect, useState } from "react";
import { LuChevronLeft, LuChevronRight, LuSearch, LuSearchX } from "react-icons/lu";
import ProductCard from "../components/productCard";
import {
  useCategoriesQuery,
  useSearchProductsQuery,
} from "../redux/api/productAPI";
import toast from "react-hot-toast";
import { errorMessage } from "../redux/api/baseQuery";
import { CartItem } from "../types/types";
import { addToCart } from "../redux/reducer/cartReducer";
import { formatINR } from "../utils/features";
import { useDispatch } from "react-redux";

const Search = () => {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("");
  const [maxPrice, setMaxPrice] = useState(100000);
  const [category, setCategory] = useState("");
  const [page, setPage] = useState(1);

  const dispatch = useDispatch();

  // Debounce typing so we query once the user pauses, and reset to page 1 on any filter change.
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput.trim()), 300);
    return () => clearTimeout(t);
  }, [searchInput]);
  useEffect(() => setPage(1), [search, sort, category, maxPrice]);

  const {
    isLoading: productsLoading,
    data: searchedData,
    isError,
    error,
  } = useSearchProductsQuery({
    search,
    sort,
    category,
    page,
    price: maxPrice,
  });

  const { data: categoriesResponse } = useCategoriesQuery();

  const addToCartHandler = (cartItem: CartItem) => {
    if (cartItem.stock < 1) return toast.error("Out of Stock");
    dispatch(addToCart(cartItem));
    toast.success("Added to Cart");
  };

  const isPreviousPage = page > 1;
  const isNextPage = page < (searchedData?.totalPage || 1);

  useEffect(() => {
    if (isError) toast.error(errorMessage(error));
  }, [isError, error]);

  return (
    <div className="search-page">
      <aside>
        <h2>Filters</h2>

        <div>
          <h4>Sort</h4>
          <select value={sort} onChange={(e) => setSort(e.target.value)}>
            <option value="">None</option>
            <option value="asc">Price (Low to High)</option>
            <option value="dsc">Price (High to Low)</option>
          </select>
        </div>

        <div>
          <h4>Max price <b>{formatINR(maxPrice)}</b></h4>
          <input
            type="range"
            min={100}
            max={100000}
            value={maxPrice}
            onChange={(e) => setMaxPrice(Number(e.target.value))}
          />
        </div>

        <div>
          <h4>Category</h4>
          <select value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">All</option>
            {categoriesResponse?.categories.map((i) => (
              <option key={i} value={i}>
                {i.charAt(0).toUpperCase() + i.slice(1)}
              </option>
            ))}
          </select>
        </div>
      </aside>

      <main>
        <header>
          <h1>All products</h1>
          {searchedData && <span>{searchedData.products.length} shown</span>}
        </header>
        <label className="search-page__box">
          <LuSearch aria-hidden />
          <input
            type="search"
            placeholder="Search products"
            aria-label="Search products"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
        </label>

        {productsLoading ? (
          <div className="product-grid">
            {Array.from({ length: 6 }, (_, i) => <div key={i} className="product-card product-card--skeleton" />)}
          </div>
        ) : (
          <div className="product-grid">
            {searchedData?.products.length === 0 && (
              <div className="empty-state">
                <LuSearchX />
                <p>No products match your filters.</p>
              </div>
            )}
            {searchedData?.products.map((i) => (
              <ProductCard
                key={i._id}
                productId={i._id}
                name={i.name}
                price={i.price}
                stock={i.stock}
              taxRate={i.taxRate}
                handler={addToCartHandler}
                photo={i.photo}
              />
            ))}
          </div>
        )}

        {searchedData && searchedData.products.length > 0 && (
          <nav className="search-page__pager" aria-label="Pagination">
            <button
              disabled={!isPreviousPage}
              onClick={() => setPage((prev) => prev - 1)}
              aria-label="Previous page"
            >
              <LuChevronLeft /> Prev
            </button>

            <span>
              Page {page} of {searchedData.totalPage}
            </span>

            <button
              disabled={!isNextPage}
              onClick={() => setPage((prev) => prev + 1)}
              aria-label="Next page"
            >
              Next <LuChevronRight />
            </button>
          </nav>
        )}
      </main>
    </div>
  );
};

export default Search;
