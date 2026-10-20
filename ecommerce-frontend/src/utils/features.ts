import { server } from "../redux/config";
import { regional } from "./money";

/** The store's time zone, or the browser's if the name is not one this browser knows. */
const zone = () => {
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: regional.timeZone });
    return regional.timeZone;
  } catch {
    return undefined;
  }
};

export const transformImage = (url?: string) => {
  if (!url) return "";
  if (/^(https?:|data:|blob:)/.test(url)) return url;
  return `${server}/${url.replace(/^\/+/, "")}`;
};

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** Labels for the last `n` months, oldest first, ending with the current month (matches API arrays). */
export const lastMonths = (n: number, now = new Date()): string[] =>
  Array.from({ length: n }, (_, i) => {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - (n - 1 - i), 1));
    return MONTHS[d.getUTCMonth()];
  });

export { formatMoney } from "./money";

export const formatDate = (iso?: string) =>
  iso ? new Date(iso).toLocaleDateString(regional.locale, { day: "2-digit", month: "short", year: "numeric", timeZone: zone() }) : "—";

/** The reference customers and admins quote: the order number, or the short id for older orders. */
export const orderLabel = (o: { _id: string; orderNumber?: string }) =>
  o.orderNumber ?? o._id.slice(-8).toUpperCase();

export const formatDateTime = (iso?: string) =>
  iso ? new Date(iso).toLocaleString(regional.locale, { dateStyle: "medium", timeStyle: "short", timeZone: zone() }) : "—";
