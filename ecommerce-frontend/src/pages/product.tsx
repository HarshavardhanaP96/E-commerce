import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useDispatch, useSelector } from "react-redux";
import toast from "react-hot-toast";
import { LuArrowLeft, LuMinus, LuPlus, LuShoppingBag } from "react-icons/lu";
import Loader from "../components/loader";
import { useProductDetailsQuery } from "../redux/api/productAPI";
import { addToCart } from "../redux/reducer/cartReducer";
import { RootState } from "../redux/store";
import { OptionAxis, Product as ProductType } from "../types/types";
import { formatINR, transformImage } from "../utils/features";
import {
  Selection,
  cartKey,
  defaultSelection,
  discountPercent,
  findVariant,
  galleryImages,
  hasVariants,
  isValueAvailable,
  priceRange,
  quantityInCart,
} from "../utils/variants";

const MAX_PER_ORDER = 100;

const ProductPage = () => {
  const { id = "" } = useParams();
  const { data, isLoading, isError } = useProductDetailsQuery(id);

  if (isLoading) return <Loader />;
  if (isError || !data) {
    return (
      <div className="product-page product-page--empty">
        <p>We couldn't find that product.</p>
        <Link to="/search">Browse products</Link>
      </div>
    );
  }
  // Keyed by id so the picked options reset when the shopper moves to another product.
  return <ProductDetail key={data.product._id} product={data.product} />;
};

const ProductDetail = ({ product }: { product: ProductType }) => {
  const dispatch = useDispatch();
  const cart = useSelector((s: RootState) => s.cartReducer.cartItems);
  const withVariants = hasVariants(product);
  const axes = product.variants?.axes ?? [];

  const [selection, setSelection] = useState<Selection>(() => defaultSelection(product));
  const [quantity, setQuantity] = useState(1);
  const [imageIndex, setImageIndex] = useState(0);

  const variant = useMemo(() => findVariant(product, selection), [product, selection]);
  const images = useMemo(() => galleryImages(product, selection), [product, selection]);
  const available = product.status === undefined || product.status === "ACTIVE";

  // What is on sale for the current choice (the product itself when it has no variants).
  const line = withVariants ? variant : { price: product.price, mrp: product.mrp, stock: product.stock, id: undefined };
  const range = priceRange(product);
  const missing = axes.filter((a) => !selection[a.name]).map((a) => a.name.toLowerCase());
  const key = cartKey({ productId: product._id, variantId: variant?.id });
  const inCart = withVariants && !variant ? 0 : quantityInCart(cart, key);
  const room = line ? Math.min(line.stock, MAX_PER_ORDER) - inCart : 0;

  useEffect(() => setImageIndex(0), [images.length, selection]);
  // Keep the stepper within what can still be added after the choice or the cart changes.
  useEffect(() => setQuantity((q) => Math.max(1, Math.min(q, room || 1))), [room]);

  const pick = (axis: OptionAxis, label: string) =>
    setSelection((s) => ({ ...s, [axis.name]: s[axis.name] === label ? "" : label }));

  const addHandler = () => {
    if (!available) return;
    if (withVariants && !variant) return toast.error(`Please choose ${missing.join(" and ")}`);
    if (!line || line.stock < 1) return toast.error("Out of Stock");
    if (room < 1) return toast.error("You already have the maximum available in your cart");
    dispatch(
      addToCart({
        productId: product._id,
        variantId: variant?.id,
        variantLabel: variant?.label,
        photo: images[0] ?? product.photo,
        name: product.name,
        price: line.price,
        quantity: inCart + quantity,
        stock: Math.min(line.stock, MAX_PER_ORDER),
        taxRate: product.taxRate,
      })
    );
    toast.success("Added to Cart");
  };

  const price = line?.price ?? range.min;
  const showFrom = withVariants && !variant && range.min !== range.max;
  const discount = discountPercent(price, line?.mrp ?? product.mrp);
  const soldOut = !!line && line.stock < 1;
  const lowStock = !!line && line.stock > 0 && line.stock <= 5;

  return (
    <div className="product-page">
      <Link to="/search" className="product-page__back">
        <LuArrowLeft /> All products
      </Link>

      <div className="product-page__grid">
        <section className="gallery" aria-label="Product images">
          <div className="gallery__main">
            {images[imageIndex] && <img src={transformImage(images[imageIndex])} alt={product.name} />}
          </div>
          {images.length > 1 && (
            <ul className="gallery__thumbs">
              {images.map((src, i) => (
                <li key={src}>
                  <button
                    className={i === imageIndex ? "is-active" : ""}
                    onClick={() => setImageIndex(i)}
                    aria-label={`Show image ${i + 1}`}
                    aria-current={i === imageIndex}
                  >
                    <img src={transformImage(src)} alt="" loading="lazy" />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="product-info">
          {product.brand && <p className="eyebrow">{product.brand}</p>}
          <h1>{product.name}</h1>

          <p className="product-info__price">
            {showFrom && <small>From </small>}
            {formatINR(price)}
            {discount > 0 && (
              <>
                <s>{formatINR((line?.mrp ?? product.mrp) as number)}</s>
                <span className="product-info__off">{discount}% off</span>
              </>
            )}
          </p>
          <small className="product-info__tax">Tax and shipping are added at checkout.</small>

          {!available && <p className="notice notice--warn">This product is not available right now.</p>}

          {axes.map((axis) => (
            <fieldset key={axis.name} className="option">
              <legend>
                {axis.name}
                {selection[axis.name] && <strong>: {selection[axis.name]}</strong>}
              </legend>
              <div className={axis.type === "COLOUR" ? "swatches" : "chips"} role="radiogroup">
                {axis.values.map((v) => {
                  const ok = isValueAvailable(product, axis, v.label, selection);
                  const on = selection[axis.name] === v.label;
                  return (
                    <button
                      key={v.label}
                      role="radio"
                      aria-checked={on}
                      aria-label={ok ? v.label : `${v.label} (sold out)`}
                      title={ok ? v.label : `${v.label} — sold out`}
                      className={`${axis.type === "COLOUR" ? "swatch" : "chip"}${on ? " is-on" : ""}${ok ? "" : " is-out"}`}
                      style={axis.type === "COLOUR" && v.hex ? { background: v.hex } : undefined}
                      onClick={() => pick(axis, v.label)}
                    >
                      {axis.type === "COLOUR" && v.hex ? <span className="sr-only">{v.label}</span> : v.label}
                    </button>
                  );
                })}
              </div>
            </fieldset>
          ))}

          <p className={`product-info__stock${soldOut ? " is-out" : ""}`} aria-live="polite">
            {withVariants && !variant
              ? `Choose ${missing.join(" and ")} to see availability`
              : soldOut
                ? "Sold out"
                : lowStock
                  ? `Only ${line?.stock} left`
                  : "In stock"}
          </p>

          <div className="product-info__buy">
            <div className="stepper">
              <button onClick={() => setQuantity((q) => q - 1)} disabled={quantity <= 1} aria-label="Decrease quantity">
                <LuMinus />
              </button>
              <span aria-live="polite">{quantity}</span>
              <button onClick={() => setQuantity((q) => q + 1)} disabled={quantity >= room} aria-label="Increase quantity">
                <LuPlus />
              </button>
            </div>
            <button className="product-info__add" onClick={addHandler} disabled={!available || soldOut}>
              <LuShoppingBag /> {withVariants && !variant ? "Select options" : "Add to cart"}
            </button>
          </div>
          {inCart > 0 && (
            <p className="product-info__incart">
              {inCart} in your cart · <Link to="/cart">View cart</Link>
            </p>
          )}

          {product.description && <p className="product-info__desc">{product.description}</p>}
          <dl className="product-info__facts">
            {variant && (
              <>
                <dt>SKU</dt>
                <dd>{variant.sku}</dd>
              </>
            )}
            {product.countryOfOrigin && (
              <>
                <dt>Country of origin</dt>
                <dd>{product.countryOfOrigin}</dd>
              </>
            )}
          </dl>
        </section>
      </div>
    </div>
  );
};

export default ProductPage;
