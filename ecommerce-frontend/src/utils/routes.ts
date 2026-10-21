/** Routes that keep the full header instead: admin has its own sidebar, checkout has its own CTA. */
export const showsBottomNav = (pathname: string) =>
  !pathname.startsWith("/admin") && !pathname.startsWith("/shipping");

/** Which token set (see _tokens.scss) a path uses. Only /admin and its children are admin. */
export const surfaceFor = (pathname: string): "store" | "admin" =>
  pathname === "/admin" || pathname.startsWith("/admin/") ? "admin" : "store";
