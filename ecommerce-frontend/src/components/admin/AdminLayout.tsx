import { ReactNode } from "react";
import AdminSidebar from "./AdminSidebar";

const AdminLayout = ({ children }: { children: ReactNode }) => (
  <div className="admin-container">
    <AdminSidebar />
    <main className="admin-main">{children}</main>
  </div>
);

export default AdminLayout;
