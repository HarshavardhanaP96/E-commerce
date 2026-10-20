import { OrderStatus } from "../../types/types";

/** "PARTIALLY_REFUNDED" → "Partially refunded"; plain words like "Shipped" pass through. */
const label = (status: string) => {
  const words = status.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
};

const StatusBadge = ({ status }: { status: OrderStatus | string }) => (
  <span className={`badge badge--${status.toLowerCase()}`}>{label(status)}</span>
);

export default StatusBadge;
