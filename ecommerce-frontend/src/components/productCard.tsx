import { Link } from "react-router-dom";
import { LuArrowRight, LuPlus } from "react-icons/lu";
import { CartItem, Product } from "../types/types";
import { formatINR, transformImage } from "../utils/features";
import { hasVariants, priceRange, swatches } from "../utils/variants";

type ProductsProps = {
  product: Product;
  handler: (item: CartItem) => void;
};

const MAX_SWATCHES = 5;

const ProductCard = ({ product, handler }: ProductsProps) => {
  const { _id: productId, photo, name, price, stock, taxRate } = product;
  const soldOut = stock < 1;
  const withVariants = hasVariants(product);
  const range = priceRange(product);
  const colours = swatches(product);
  const to = `/product/${productId}`;

  return (
    <article className="product-card">
      <Link to={to} className="product-card__media" aria-label={`View ${name}`}>
        <img src={transformImage(photo)} alt={name} loading="lazy" />
        {soldOut ? (
          <span className="product-card__tag product-card__tag--muted">Sold out</span>
        ) : (
          stock <= 5 && <span className="product-card__tag">Only {stock} left</span>
        )}
      </Link>

      <div className="product-card__body">
        <div>
          <h3 title={name}>
            <Link to={to}>{name}</Link>
          </h3>
          <p className="product-card__price">
            {withVariants && range.min !== range.max && <small>From </small>}
            {formatINR(withVariants ? range.min : price)}
          </p>
          {colours.length > 0 && (
            <ul className="swatches swatches--small" aria-label="Available colours">
              {colours.slice(0, MAX_SWATCHES).map((c) => (
                <li key={c.label} title={c.label} style={{ background: c.hex }}>
                  <span className="sr-only">{c.label}</span>
                </li>
              ))}
              {colours.length > MAX_SWATCHES && <li className="swatches__more">+{colours.length - MAX_SWATCHES}</li>}
            </ul>
          )}
        </div>
        {withVariants ? (
          <Link
            to={to}
            className="product-card__add product-card__add--options"
            aria-label={`Choose options for ${name}`}
            title="Choose options"
          >
            <LuArrowRight />
          </Link>
        ) : (
          <button
            className="product-card__add"
            onClick={() => handler({ productId, photo, name, price, quantity: 1, stock, taxRate })}
            disabled={soldOut}
            aria-label={`Add ${name} to cart`}
            title="Add to cart"
          >
            <LuPlus />
          </button>
        )}
      </div>
    </article>
  );
};

export default ProductCard;
