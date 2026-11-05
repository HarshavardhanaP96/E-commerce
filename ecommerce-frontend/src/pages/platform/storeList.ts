import type { PlatformTenant } from "../../redux/api/platformAPI";

export { typeLabel } from "../../utils/storeTypes";

export type StatusChip = "all" | "active" | "trial" | "suspended" | "closing";

export const CHIPS: { key: StatusChip; label: string }[] = [
  { key: "all", label: "All" },
  { key: "active", label: "Active" },
  { key: "trial", label: "Trial" },
  { key: "suspended", label: "Suspended" },
  { key: "closing", label: "Closing" },
];

const CHIP_STATUSES: Record<Exclude<StatusChip, "all">, PlatformTenant["status"][]> = {
  active: ["ACTIVE"],
  trial: ["TRIAL"],
  suspended: ["SUSPENDED"],
  closing: ["CANCELLED", "SCHEDULED_FOR_DELETION"],
};

export const byChip = (tenants: PlatformTenant[], chip: StatusChip) =>
  chip === "all" ? tenants : tenants.filter((t) => CHIP_STATUSES[chip].includes(t.status));

export const domainSummary = (domains: string[]) =>
  domains.length === 0 ? "—" : domains.length === 1 ? domains[0] : `${domains[0]} +${domains.length - 1}`;

/** The workspace URL carries the slug; the API is addressed by id. */
export const findBySlug = (tenants: PlatformTenant[] | undefined, slug: string | undefined) =>
  slug ? tenants?.find((t) => t.slug === slug) : undefined;

const day = (iso: string) => new Date(iso).toLocaleDateString("en-IN", { dateStyle: "medium", timeZone: "UTC" });

export const statusNote = (t: PlatformTenant) =>
  t.status === "TRIAL" && t.trialEndsAt
    ? `Trial ends ${day(t.trialEndsAt)}`
    : t.status === "SCHEDULED_FOR_DELETION" && t.deletionDueAt
      ? `Data deleted on ${day(t.deletionDueAt)}`
      : null;
