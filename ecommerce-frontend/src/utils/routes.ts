/** Routes that keep the full header instead: admin has its own sidebar, checkout has its own CTA. */
export const showsBottomNav = (pathname: string) =>
  !pathname.startsWith("/admin") && !pathname.startsWith("/shipping");
