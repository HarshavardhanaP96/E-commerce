import type { PlatformTenant } from "../../redux/api/platformAPI";

export type StatusAction = "activate" | "suspend" | "cancel" | "offboard" | "restore";

/** Same rules as the API (TenantAdminService): the default store never moves; a scheduled deletion only restores. */
export const statusMoves = (t: Pick<PlatformTenant, "slug" | "status">): StatusAction[] => {
  if (t.status === "DELETED") return [];
  if (t.status === "SCHEDULED_FOR_DELETION") return ["restore"];
  if (t.slug === "default") return [];
  const moves: StatusAction[] = [];
  if (t.status !== "ACTIVE") moves.push("activate");
  if (t.status === "ACTIVE" || t.status === "TRIAL") moves.push("suspend");
  if (t.status !== "CANCELLED") moves.push("cancel");
  moves.push("offboard");
  return moves;
};

export const MOVE_COPY: Record<StatusAction, { label: string; title: string; body: (name: string) => string; danger: boolean }> = {
  activate: {
    label: "Activate",
    title: "Activate store",
    danger: false,
    body: (n) => `${n} opens to shoppers again. A trial becomes a normal store.`,
  },
  suspend: {
    label: "Suspend",
    title: "Suspend store",
    danger: true,
    body: (n) => `${n}'s storefront stops working until you activate it again. Nothing is deleted.`,
  },
  cancel: {
    label: "Cancel",
    title: "Cancel store",
    danger: true,
    body: (n) => `Use this when the owner leaves. ${n}'s storefront closes; you can activate or offboard it later.`,
  },
  offboard: {
    label: "Offboard",
    title: "Offboard store",
    danger: true,
    body: (n) =>
      `${n}'s storefront closes now, and all its data and photos are deleted after the grace period. Export it first if its data is needed.`,
  },
  restore: {
    label: "Restore",
    title: "Restore store",
    danger: false,
    body: (n) => `Cancels the scheduled deletion. ${n} becomes active again.`,
  },
};
