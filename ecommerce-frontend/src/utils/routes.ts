/** The store admin and the platform console: own sidebar, admin tokens, no storefront footer or bottom nav. */
export const isConsolePath = (pathname: string) => /^\/(admin|platform)(\/|$)/.test(pathname);

/** Routes that keep the full header instead: consoles have their own sidebar, checkout has its own CTA. */
export const showsBottomNav = (pathname: string) => !isConsolePath(pathname) && !pathname.startsWith("/shipping");

/** Admin pages that exist only while the store's feature switch is on (tenant config `features`). */
const FEATURE_PAGES: Record<string, string> = { "/admin/app/marketing": "marketing" };

/** False for a switched page whose feature is off or not known yet (the API would answer 404). */
export const adminPageVisible = (url: string, features: Record<string, boolean>) => {
  const key = FEATURE_PAGES[url];
  return !key || features[key] === true;
};

/** Which token set (see _tokens.scss) a path uses: the admin and the platform console are admin. */
export const surfaceFor = (pathname: string): "store" | "admin" => (isConsolePath(pathname) ? "admin" : "store");
