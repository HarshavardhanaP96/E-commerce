import { ReactNode } from "react";
import { LuHistory, LuLogOut, LuPlusCircle, LuStore } from "react-icons/lu";
import AdminLayout from "../../components/admin/AdminLayout";
import { Sidebar } from "../../components/admin/AdminSidebar";
import { PLATFORM_EMAIL_KEY, signOutPlatform } from "../../redux/api/platformAPI";

const PlatformSidebar = () => (
  <Sidebar
    head={
      <>
        <strong>Platform console</strong>
        <small className="platform-sidebar__who">{localStorage.getItem(PLATFORM_EMAIL_KEY) ?? ""}</small>
        <button type="button" className="btn btn--sm platform-sidebar__out" onClick={signOutPlatform}>
          <LuLogOut aria-hidden /> Sign out
        </button>
      </>
    }
    sections={[
      {
        title: "Stores",
        items: [
          { url: "/platform/stores", text: "Stores", Icon: LuStore, end: true },
          { url: "/platform/stores/new", text: "Onboard store", Icon: LuPlusCircle },
        ],
      },
      { title: "Records", items: [{ url: "/platform/audit", text: "Audit log", Icon: LuHistory }] },
    ]}
  />
);

/** Every signed-in console page: the admin shell with the platform sidebar. */
export const PlatformShell = ({ children }: { children: ReactNode }) => (
  <AdminLayout sidebar={<PlatformSidebar />}>{children}</AdminLayout>
);

export default PlatformSidebar;
