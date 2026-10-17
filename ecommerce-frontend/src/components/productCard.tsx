import { LuPlus } from "react-icons/lu";
import { CartItem } from "../types/types";
import { formatINR, transformImage } from "../utils/features";

type ProductsProps = {
  productId: string;
  photo: string;
  name: string;
  price: number;
  stock: number;
  taxRate?: number;
  handler: (item: CartItem) => void;
};

const ProductCard = ({
  productId,
  photo,
  name,
  price,
  stock,
  taxRate,
  handler,
}: ProductsProps) => {
  const soldOut = stock < 1;

  return (
    <article className="product-card">
      <div className="product-card__media">
        <img src={transformImage(photo)} alt={name} loading="lazy" />
        {soldOut ? (
          <span className="product-card__tag product-card__tag--muted">Sold out</span>
        ) : (
          stock <= 5 && <span className="product-card__tag">Only {stock} left</span>
        )}
      </div>

      <div className="product-card__body">
        <div>
          <h3 title={name}>{name}</h3>
          <p className="product-card__price">{formatINR(price)}</p>
        </div>
        <button
          className="product-card__add"
          onClick={() =>
            handler({ productId, photo, name, price, quantity: 1, stock, taxRate })
          }
          disabled={soldOut}
          aria-label={`Add ${name} to cart`}
          title="Add to cart"
        >
          <LuPlus />
        </button>
      </div>
    </article>
  );
};

export default ProductCard;
