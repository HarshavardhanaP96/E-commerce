import { OrderStatus } from "../../types/types";

const StatusBadge = ({ status }: { status: OrderStatus | string }) => (
  <span className={`badge badge--${status.toLowerCase()}`}>{status}</span>
);

export default StatusBadge;
