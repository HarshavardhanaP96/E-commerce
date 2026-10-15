import { useState } from "react";
import { Link } from "react-router-dom";
import {
  FaSearch,
  FaShoppingBag,
  FaSignInAlt,
  FaSignOutAlt,
  FaUser,
  FaTachometerAlt,
  FaClipboardList,
} from "react-icons/fa";
import { useSelector, useDispatch } from "react-redux";
import { RootState } from "../redux/store";
import { userNotExist } from "../redux/reducer/userReducer";
import toast from "react-hot-toast";
import { transformImage } from "../utils/features";

const Header = () => {
  const { user } = useSelector((state: RootState) => state.userReducer);
  const [isOpen, setIsOpen] = useState<boolean>(false);
  const dispatch = useDispatch();

  const logoutHandler = () => {
    dispatch(userNotExist());
    toast.success("Signed Out Successfully");
    setIsOpen(false);
  };

  return (
    <nav className="header">
      <Link className="header__logo" onClick={() => setIsOpen(false)} to={"/"}>
        6PACK
      </Link>

      <Link onClick={() => setIsOpen(false)} to={"/search"} aria-label="Search products">
        <FaSearch />
      </Link>

      <Link onClick={() => setIsOpen(false)} to={"/cart"} aria-label="Shopping cart">
        <FaShoppingBag />
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
                <FaUser />
             )}
          </button>

          <dialog open={isOpen} className="header__menu">
            <div>
              {user?.role === "admin" && (
                <Link onClick={() => setIsOpen(false)} to={"/admin/analytics"}>
                  <FaTachometerAlt />
                  Analytics
                </Link>
              )}

              <Link onClick={() => setIsOpen(false)} to={"/orders"}>
                <FaClipboardList />
                Orders
              </Link>

              <button onClick={logoutHandler} aria-label="Sign out">
                <FaSignOutAlt />
                Sign Out
              </button>
            </div>
          </dialog>
        </>
      ) : (
        <Link to={"/login"} aria-label="Sign in">
          <FaSignInAlt />
        </Link>
      )}
    </nav>
  );
};

export default Header;
