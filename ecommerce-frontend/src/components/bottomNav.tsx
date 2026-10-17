import { useEffect, useRef, useState } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { LuHome, LuLogIn, LuSearch, LuShoppingBag, LuUser } from "react-icons/lu";
import { useSelector } from "react-redux";
import { RootState } from "../redux/store";
import { transformImage } from "../utils/features";
import { showsBottomNav } from "../utils/routes";
import AccountMenu from "./accountMenu";

const isAccountRoute = (pathname: string) =>
  pathname === "/login" || pathname === "/orders" || pathname.startsWith("/order/") ||
  pathname.startsWith("/account");

/** Mobile-only tab bar (CSS hides it above the breakpoint); replaces the header's icon actions. */
const BottomNav = () => {
  const { pathname } = useLocation();
  const { user } = useSelector((state: RootState) => state.userReducer);
  const cartCount = useSelector((state: RootState) =>
    state.cartReducer.cartItems.reduce((n, i) => n + i.quantity, 0)
  );
  const [sheetOpen, setSheetOpen] = useState(false);
  const sheetRef = useRef<HTMLDialogElement>(null);

  // showModal() gives focus trapping, Escape-to-close and an inert page behind the sheet.
  useEffect(() => {
    const sheet = sheetRef.current;
    if (!sheet) return;
    if (sheetOpen && !sheet.open) sheet.showModal();
    if (!sheetOpen && sheet.open) sheet.close();
  }, [sheetOpen]);

  useEffect(() => setSheetOpen(false), [pathname]);

  if (!showsBottomNav(pathname)) return null;

  return (
    <nav className="bottom-nav" aria-label="Primary">
      <NavLink to={"/"} end>
        <LuHome />
        <span>Home</span>
      </NavLink>

      <NavLink to={"/search"}>
        <LuSearch />
        <span>Search</span>
      </NavLink>

      <NavLink to={"/cart"} aria-label={`Cart, ${cartCount} items`}>
        <LuShoppingBag />
        {cartCount > 0 && (
          <span className="bottom-nav__count" aria-hidden="true">
            {cartCount > 99 ? "99+" : cartCount}
          </span>
        )}
        <span>Cart</span>
      </NavLink>

      {user?._id ? (
        <>
          <button
            className={isAccountRoute(pathname) ? "active" : undefined}
            onClick={() => setSheetOpen(true)}
            aria-haspopup="dialog"
            aria-expanded={sheetOpen}
          >
            {user.photo ? (
              <img className="bottom-nav__avatar" src={transformImage(user.photo)} alt="" />
            ) : (
              <LuUser />
            )}
            <span>Account</span>
          </button>

          <dialog
            ref={sheetRef}
            className="bottom-nav__sheet"
            aria-label="Account"
            onClose={() => setSheetOpen(false)}
            // A click whose target is the dialog itself landed on the backdrop.
            onClick={(e) => e.target === e.currentTarget && setSheetOpen(false)}
          >
            <AccountMenu user={user} onClose={() => setSheetOpen(false)} />
          </dialog>
        </>
      ) : (
        <NavLink to={"/login"}>
          <LuLogIn />
          <span>Sign in</span>
        </NavLink>
      )}
    </nav>
  );
};

export default BottomNav;
