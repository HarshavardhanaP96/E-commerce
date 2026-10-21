import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { LuChevronLeft, LuChevronRight, LuSearch, LuSearchX, LuSlidersHorizontal, LuX } from "react-icons/lu";
import ProductCard from "../components/productCard";
import {
  useCategoriesQuery,
  useOptionFacetsQuery,
  useSearchProductsQuery,
} from "../redux/api/productAPI";
import toast from "react-hot-toast";
import { errorMessage } from "../redux/api/baseQuery";
import { CartItem } from "../types/types";
import { addToCart } from "../redux/reducer/cartReducer";
import { formatMoney } from "../utils/features";
import { MAX_PRICE, SORTS, activeFilters } from "../utils/filters";
import { useDispatch } from "react-redux";

const Search = () => {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [sort, setSort] = useState("");
  const [maxPrice, setMaxPrice] = useState(MAX_PRICE);
  const [params] = useSearchParams();
  const [category, setCategory] = useState(params.get("category") ?? "");
  const [page, setPage] = useState(1);
  // Picked option values, one per option name: { Size: "M", Colour: "Black" }.
  const [picked, setPicked] = useState<Record<string, string>>({});
  // Phones: the filters live in a bottom panel (a modal <dialog>); wider screens show them as the sidebar.
  const [filtersOpen, setFiltersOpen] = useState(false);
  const filtersRef = useRef<HTMLDialogElement>(null);

  const dispatch = useDispatch();

  // Debounce typing so we query once the user pauses, and reset to page 1 on any filter change.
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput.trim()), 300);
    return () => clearTimeout(t);
  }, [searchInput]);
  const options = Object.entries(picked).map(([name, value]) => `${name}:${value}`);
  const optionsKey = options.join("|");
  useEffect(() => setPage(1), [search, sort, category, maxPrice, optionsKey]);

  // showModal() gives focus trapping, Escape-to-close and an inert page behind the panel.
  useEffect(() => {
    const panel = filtersRef.current;
    if (!panel) return;
    if (filtersOpen && !panel.open) panel.showModal();
    if (!filtersOpen && panel.open) panel.close();
  }, [filtersOpen]);

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
    options,
  });

  const { data: categoriesResponse } = useCategoriesQuery();
  const { data: facetsResponse } = useOptionFacetsQuery();
  const togglePick = (name: string, value: string) =>
    setPicked((p) => {
      const { [name]: current, ...rest } = p;
      return current === value ? rest : { ...rest, [name]: value };
    });

  const chips = activeFilters({ search, category, maxPrice, picked }, formatMoney);
  const clearFilter = (key: string) => {
    if (key === "search") {
      setSearchInput("");
      setSearch("");
    } else if (key === "category") setCategory("");
    else if (key === "price") setMaxPrice(MAX_PRICE);
    else
      setPicked((p) => {
        const next = { ...p };
        delete next[key.slice("option:".length)];
        return next;
      });
  };
  const clearAll = () => {
    setSearchInput("");
    setSearch("");
    setCategory("");
    setMaxPrice(MAX_PRICE);
    setPicked({});
    setSort("");
  };

  const addToCartHandler = (cartItem: CartItem) => {
    if (cartItem.stock < 1) return toast.error("Out of stock");
    dispatch(addToCart(cartItem));
    toast.success("Added to cart");
  };

  const isPreviousPage = page > 1;
  const isNextPage = page < (searchedData?.totalPage || 1);

  useEffect(() => {
    if (isError) toast.error(errorMessage(error));
  }, [isError, error]);

  return (
    <div className="search-page">
      <aside>
        <dialog ref={filtersRef} className="search-filters" aria-label="Filters" onClose={() => setFiltersOpen(false)}>
          <h2>Filters</h2>

          <div>
            <h4 id="sort-label">Sort</h4>
            <div className="chips" role="radiogroup" aria-labelledby="sort-label">
              {SORTS.map((s) => (
                <button
                  key={s.value}
                  type="button"
                  role="radio"
                  aria-checked={sort === s.value}
                  className={`chip${sort === s.value ? " is-on" : ""}`}
                  onClick={() => setSort(s.value)}
                >
                  {s.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <h4>
              <label htmlFor="max-price">Max price</label>
            </h4>
            <div className="search-page__price">
              <input
                type="range"
                min={100}
                max={MAX_PRICE}
                step={100}
                value={maxPrice}
                aria-label="Max price"
                onChange={(e) => setMaxPrice(Number(e.target.value))}
              />
              <input
                id="max-price"
                type="number"
                inputMode="numeric"
                min={100}
                max={MAX_PRICE}
                value={maxPrice}
                onChange={(e) => setMaxPrice(Math.min(MAX_PRICE, Math.max(100, Number(e.target.value) || 100)))}
              />
            </div>
          </div>

          <div>
            <h4>
              <label htmlFor="category">Category</label>
            </h4>
            <select id="category" value={category} onChange={(e) => setCategory(e.target.value)}>
              <option value="">All</option>
              {categoriesResponse?.categories.map((i) => (
                <option key={i} value={i}>
                  {i.charAt(0).toUpperCase() + i.slice(1)}
                </option>
              ))}
            </select>
          </div>

          {facetsResponse?.options.map((facet) => (
            <div key={facet.name} className="search-options">
              <h4>{facet.name}</h4>
              <div className={facet.type === "COLOUR" ? "swatches" : "chips"} role="group" aria-label={facet.name}>
                {facet.values.map((v) => {
                  const on = picked[facet.name] === v.label;
                  return (
                    <button
                      key={v.label}
                      aria-pressed={on}
                      title={v.label}
                      className={`${facet.type === "COLOUR" && v.hex ? "swatch" : "chip"}${on ? " is-on" : ""}`}
                      style={facet.type === "COLOUR" && v.hex ? { background: v.hex } : undefined}
                      onClick={() => togglePick(facet.name, v.label)}
                    >
                      {facet.type === "COLOUR" && v.hex ? <span className="sr-only">{v.label}</span> : v.label}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}

          <div className="search-filters__footer">
            <button type="button" className="btn btn--primary" onClick={() => setFiltersOpen(false)}>
              Show {searchedData?.total ?? 0} results
            </button>
          </div>
        </dialog>
      </aside>

      <main>
        <header>
          <h1>All products</h1>
          {searchedData && <span>{searchedData.total} {searchedData.total === 1 ? "product" : "products"}</span>}
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

        <button type="button" className="btn search-page__filters-btn" onClick={() => setFiltersOpen(true)}>
          <LuSlidersHorizontal aria-hidden /> Filters{chips.length > 0 && ` (${chips.length})`}
        </button>

        {chips.length > 0 && (
          <div className="active-filters" role="group" aria-label="Active filters">
            {chips.map((f) => (
              <button
                key={f.key}
                type="button"
                className="chip chip--removable"
                onClick={() => clearFilter(f.key)}
                aria-label={`Remove filter ${f.label}`}
              >
                {f.label} <LuX aria-hidden />
              </button>
            ))}
            <button type="button" className="link-button" onClick={clearAll}>
              Clear all
            </button>
          </div>
        )}

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
                {chips.length > 0 && (
                  <button type="button" className="btn" onClick={clearAll}>
                    Clear all filters
                  </button>
                )}
              </div>
            )}
            {searchedData?.products.map((i) => (
              <ProductCard key={i._id} product={i} handler={addToCartHandler} />
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
