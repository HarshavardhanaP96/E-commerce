import { server } from "../redux/config";

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

export const formatINR = (value: number) =>
  `₹${value.toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;

export const formatDate = (iso?: string) =>
  iso ? new Date(iso).toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" }) : "—";
