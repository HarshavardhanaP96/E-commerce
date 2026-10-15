import { FaTrash, FaMinus, FaPlus } from "react-icons/fa";
import { CartItem } from "../types/types";
import { transformImage } from "../utils/features";

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
        <p>{name}</p>
        <span>₹{price.toLocaleString()}</span>
      </article>

      <div>
        <button 
          onClick={() => decrementHandler(cartItem)}
          aria-label="Decrease quantity"
          disabled={quantity <= 1}
        >
          <FaMinus />
        </button>
        <span>{quantity}</span>
        <button 
          onClick={() => incrementHandler(cartItem)}
          aria-label="Increase quantity"
          disabled={quantity >= cartItem.stock}
        >
          <FaPlus />
        </button>
      </div>

      <button 
        onClick={() => removeHandler(productId)}
        aria-label={`Remove ${name} from cart`}
      >
        <FaTrash />
      </button>
    </div>
  );
};

export default CartItems;
