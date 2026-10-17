import { Link } from "react-router-dom";
import { LuLayoutDashboard, LuHelpCircle, LuLogOut, LuMapPin, LuPackage, LuShieldCheck } from "react-icons/lu";
import { useDispatch } from "react-redux";
import toast from "react-hot-toast";
import { User } from "../types/types";
import { userNotExist } from "../redux/reducer/userReducer";

/** Signed-in user's menu body; shared by the header dropdown and the mobile bottom sheet. */
const AccountMenu = ({ user, onClose }: { user: User; onClose: () => void }) => {
  const dispatch = useDispatch();

  const logoutHandler = () => {
    dispatch(userNotExist());
    toast.success("Signed Out Successfully");
    onClose();
  };

  return (
    <div className="account-menu">
      <div className="account-menu__who">
        <strong>{user.name}</strong>
        <small>{user.email}</small>
      </div>
      <div>
        {user.role === "admin" && (
          <Link onClick={onClose} to={"/admin/analytics"}>
            <LuLayoutDashboard />
            Admin dashboard
          </Link>
        )}

        <Link onClick={onClose} to={"/orders"}>
          <LuPackage />
          My orders
        </Link>

        <Link onClick={onClose} to={"/account/addresses"}>
          <LuMapPin />
          Saved addresses
        </Link>
        <Link onClick={onClose} to={"/account/privacy"}>
          <LuShieldCheck />
          Privacy &amp; data
        </Link>

        <Link onClick={onClose} to={"/help"}>
          <LuHelpCircle />
          Help Centre
        </Link>

        <button onClick={logoutHandler}>
          <LuLogOut />
          Sign out
        </button>
      </div>
    </div>
  );
};

export default AccountMenu;
