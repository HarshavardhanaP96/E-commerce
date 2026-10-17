import { useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { LuBookOpen, LuLogIn, LuSearch, LuShoppingBag, LuUser } from "react-icons/lu";
import { useSelector } from "react-redux";
import { RootState } from "../redux/store";
import { transformImage } from "../utils/features";
import AccountMenu from "./accountMenu";
import { showsBottomNav } from "../utils/routes";
import { guideForPath } from "../help/adminGuides";

const Header = () => {
  const { user } = useSelector((state: RootState) => state.userReducer);
  const cartCount = useSelector((state: RootState) =>
    state.cartReducer.cartItems.reduce((n, i) => n + i.quantity, 0)
  );
  const { pathname } = useLocation();
  const isAdmin = pathname.startsWith("/admin");
  const [isOpen, setIsOpen] = useState<boolean>(false);
  // Admin pages: one-click link to the guide for the page being viewed (empty on the help pages themselves).
  const guide = isAdmin ? guideForPath(pathname) : null;

  return (
    // --compact: on mobile the bottom nav carries these actions, so the header shows only the logo.
    <nav className={`header${showsBottomNav(pathname) ? " header--compact" : ""}`} aria-label="Site">
      <Link className="header__logo" onClick={() => setIsOpen(false)} to={"/"}>
        6PACK<span>.</span>
        {isAdmin && <small>Admin</small>}
      </Link>

      {guide !== null && (
        <Link
          className="header__help"
          onClick={() => setIsOpen(false)}
          to={guide ? `/admin/help/${guide}` : "/admin/help"}
          aria-label={guide ? "Help for this page" : "All help guides"}
          title={guide ? "Guide for this page" : "All guides"}
        >
          <LuBookOpen />
          <span>Help</span>
        </Link>
      )}

      <Link onClick={() => setIsOpen(false)} to={"/search"} aria-label="Search products" title="Search">
        <LuSearch />
      </Link>

      <Link onClick={() => setIsOpen(false)} to={"/cart"} aria-label={`Cart, ${cartCount} items`} title="Cart">
        <LuShoppingBag />
        {cartCount > 0 && <span className="header__count">{cartCount > 99 ? "99+" : cartCount}</span>}
      </Link>

      {user?._id ? (
        <>
          <button
            onClick={() => setIsOpen(!isOpen)}
            aria-label="User menu"
            aria-expanded={isOpen}
          >
             {user?.photo ? (
                <img className="header__avatar" src={transformImage(user.photo)} alt={user?.name || "User"} />
             ) : (
                <LuUser />
             )}
          </button>

          <dialog open={isOpen} className="header__menu">
            <AccountMenu user={user} onClose={() => setIsOpen(false)} />
          </dialog>
        </>
      ) : (
        <Link to={"/login"} aria-label="Sign in" title="Sign in">
          <LuLogIn />
        </Link>
      )}
    </nav>
  );
};

export default Header;
