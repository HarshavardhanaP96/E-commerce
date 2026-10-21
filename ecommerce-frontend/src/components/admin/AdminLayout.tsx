import { ReactNode } from "react";
import AdminSidebar from "./AdminSidebar";
import PageHeader from "./PageHeader";

type Props = { children: ReactNode; title?: string; description?: string; actions?: ReactNode };

/** Admin shell. With a `title` the page gets the shared PageHeader; pages with their own header leave it out. */
const AdminLayout = ({ children, title, description, actions }: Props) => (
  <div className="admin-container">
    <AdminSidebar />
    <main className="admin-main">
      {title && <PageHeader title={title} description={description} actions={actions} />}
      {children}
    </main>
  </div>
);

export default AdminLayout;
