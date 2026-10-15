import { LuMinus, LuPlus, LuTrash2 } from "react-icons/lu";
import { CartItem } from "../types/types";
import { formatINR, transformImage } from "../utils/features";

type CartItemsProps = {
  cartItem: CartItem;
  incrementHandler: (cartItem: CartItem) => void;
  decrementHandler: (cartItem: CartItem) => void;
  removeHandler: (id: string) => void;
};

const CartItems = ({
  cartItem,
  incrementHandler,
  decrementHandler,
  removeHandler,
}: CartItemsProps) => {
  const { photo, name, productId, price, quantity } = cartItem;

  return (
    <div className="CartItem">
      <img src={transformImage(photo)} alt={name} loading="lazy" />
      <article>
        <p title={name}>{name}</p>
        <span>{formatINR(price)} each</span>
      </article>

      <div>
        <button
          onClick={() => decrementHandler(cartItem)}
          aria-label="Decrease quantity"
          disabled={quantity <= 1}
        >
          <LuMinus />
        </button>
        <span aria-live="polite">{quantity}</span>
        <button
          onClick={() => incrementHandler(cartItem)}
          aria-label="Increase quantity"
          disabled={quantity >= cartItem.stock}
        >
          <LuPlus />
        </button>
      </div>

      <button
        onClick={() => removeHandler(productId)}
        aria-label={`Remove ${name} from cart`}
        title="Remove"
      >
        <LuTrash2 />
      </button>
    </div>
  );
};

export default CartItems;
