/** Routes that keep the full header instead: admin has its own sidebar, checkout has its own CTA. */
export const showsBottomNav = (pathname: string) =>
  !pathname.startsWith("/admin") && !pathname.startsWith("/shipping");

/** Admin pages that exist only while the store's feature switch is on (tenant config `features`). */
const FEATURE_PAGES: Record<string, string> = { "/admin/app/marketing": "marketing" };

/** False for a switched page whose feature is off or not known yet (the API would answer 404). */
export const adminPageVisible = (url: string, features: Record<string, boolean>) => {
  const key = FEATURE_PAGES[url];
  return !key || features[key] === true;
};

/** Which token set (see _tokens.scss) a path uses. Only /admin and its children are admin. */
export const surfaceFor = (pathname: string): "store" | "admin" =>
  pathname === "/admin" || pathname.startsWith("/admin/") ? "admin" : "store";
