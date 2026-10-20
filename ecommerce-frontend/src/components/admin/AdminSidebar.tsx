import { useEffect, useState } from "react";
import { IconType } from "react-icons";
import {
  LuBarChart3,
  LuBookOpen,
  LuFolderTree,
  LuHistory,
  LuMenu,
  LuPackage,
  LuReceipt,
  LuSettings,
  LuTicket,
  LuUsers,
  LuX,
} from "react-icons/lu";
import { NavLink, useLocation } from "react-router-dom";

const NAV: { url: string; text: string; Icon: IconType }[] = [
  { url: "/admin/analytics", text: "Analytics", Icon: LuBarChart3 },
  { url: "/admin/product", text: "Products", Icon: LuPackage },
  { url: "/admin/category", text: "Categories", Icon: LuFolderTree },
  { url: "/admin/customer", text: "Customers", Icon: LuUsers },
  { url: "/admin/transaction", text: "Transactions", Icon: LuReceipt },
  { url: "/admin/app/coupon", text: "Coupons", Icon: LuTicket },
  { url: "/admin/audit", text: "Audit log", Icon: LuHistory },
  { url: "/admin/settings", text: "Store settings", Icon: LuSettings },
];

const SUPPORT: typeof NAV = [{ url: "/admin/help", text: "Help & guides", Icon: LuBookOpen }];

/** Fixed sidebar on desktop; an off-canvas drawer below 1100px (see .admin-sidebar in app.scss). */
const AdminSidebar = () => {
  const [open, setOpen] = useState(false);
  const { pathname } = useLocation();

  // Close the drawer after navigating.
  useEffect(() => setOpen(false), [pathname]);

  return (
    <>
      <button id="hamburger" onClick={() => setOpen(true)} aria-label="Open menu">
        <LuMenu />
      </button>
      {open && <div className="admin-sidebar__backdrop" onClick={() => setOpen(false)} />}

      <aside className={`admin-sidebar${open ? " admin-sidebar--open" : ""}`}>
        <div className="admin-sidebar__top">
          <strong>Menu</strong>
          <button className="icon-btn" onClick={() => setOpen(false)} aria-label="Close menu">
            <LuX />
          </button>
        </div>

        <h5>Manage</h5>
        <ul>
          {NAV.map(({ url, text, Icon }) => (
            <li key={url}>
              <NavLink to={url}>
                <Icon aria-hidden />
                {text}
              </NavLink>
            </li>
          ))}
        </ul>

        <h5>Support</h5>
        <ul>
          {SUPPORT.map(({ url, text, Icon }) => (
            <li key={url}>
              <NavLink to={url}>
                <Icon aria-hidden />
                {text}
              </NavLink>
            </li>
          ))}
        </ul>
      </aside>
    </>
  );
};

export default AdminSidebar;
