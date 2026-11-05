import { Fragment, ReactNode, useEffect, useState } from "react";
import { IconType } from "react-icons";
import {
  LuBarChart3,
  LuBookOpen,
  LuFolderTree,
  LuHistory,
  LuMegaphone,
  LuMenu,
  LuPackage,
  LuReceipt,
  LuSettings,
  LuTicket,
  LuUsers,
  LuX,
} from "react-icons/lu";
import { Link, NavLink, useLocation } from "react-router-dom";
import { brand, features } from "../../tenant/tenant";
import { adminPageVisible } from "../../utils/routes";

const NAV: { url: string; text: string; Icon: IconType }[] = [
  { url: "/admin/analytics", text: "Analytics", Icon: LuBarChart3 },
  { url: "/admin/product", text: "Products", Icon: LuPackage },
  { url: "/admin/category", text: "Categories", Icon: LuFolderTree },
  { url: "/admin/customer", text: "Customers", Icon: LuUsers },
  { url: "/admin/transaction", text: "Transactions", Icon: LuReceipt },
  { url: "/admin/app/coupon", text: "Coupons", Icon: LuTicket },
  { url: "/admin/app/marketing", text: "Marketing", Icon: LuMegaphone },
  { url: "/admin/audit", text: "Audit log", Icon: LuHistory },
  { url: "/admin/settings", text: "Store settings", Icon: LuSettings },
];

const SUPPORT: typeof NAV = [{ url: "/admin/help", text: "Help & guides", Icon: LuBookOpen }];

export type SidebarSection = { title: string; items: { url: string; text: string; Icon: IconType; end?: boolean }[] };

/** Fixed sidebar on wide screens, an icon rail at 1101–1280px, an off-canvas drawer below (see .admin-sidebar in app.scss). */
export const Sidebar = ({ head, sections }: { head: ReactNode; sections: SidebarSection[] }) => {
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

        <div className="admin-sidebar__store">{head}</div>

        {/* Fragments, not wrappers: the CSS spaces sections with h5:not(:first-of-type). */}
        {sections.map((section) => (
          <Fragment key={section.title}>
            <h5>{section.title}</h5>
            <ul>
              {section.items.map(({ url, text, Icon, end }) => (
                <li key={url}>
                  <NavLink to={url} title={text} end={end}>
                    <Icon aria-hidden />
                    <span className="admin-sidebar__label">{text}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </Fragment>
        ))}
      </aside>
    </>
  );
};

const AdminSidebar = () => (
  <Sidebar
    head={
      <>
        <strong>{brand.name}</strong>
        <Link to="/">View store</Link>
      </>
    }
    sections={[
      { title: "Manage", items: NAV.filter(({ url }) => adminPageVisible(url, features)) },
      { title: "Support", items: SUPPORT },
    ]}
  />
);

export default AdminSidebar;
