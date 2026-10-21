import { ReactNode } from "react";
import { LuArrowLeft } from "react-icons/lu";
import { Link, useNavigate } from "react-router-dom";
import AdminLayout from "./AdminLayout";

type Props = {
  title: string;
  /** The list this record belongs to, shown as a breadcrumb above the title. */
  parent: { to: string; label: string };
  subtitle?: ReactNode;
  /** Save / Update / Delete buttons, shown at the top-right. */
  actions?: ReactNode;
  children: ReactNode;
};

/** Shared shell for product/user/order detail pages: fits the viewport, actions top-right. */
const DetailLayout = ({ title, parent, subtitle, actions, children }: Props) => {
  const navigate = useNavigate();
  return (
    <AdminLayout>
      <div className="detail-page">
        <header className="detail-page__header">
          <button className="icon-btn" onClick={() => navigate(-1)} aria-label="Back">
            <LuArrowLeft />
          </button>
          <div className="detail-page__title">
            <nav aria-label="Breadcrumb" className="breadcrumb">
              <Link to={parent.to}>{parent.label}</Link>
              <span aria-hidden>/</span>
            </nav>
            <h1>{title}</h1>
            {subtitle && <small>{subtitle}</small>}
          </div>
          {actions && <div className="detail-page__actions">{actions}</div>}
        </header>
        <div className="detail-page__body">{children}</div>
      </div>
    </AdminLayout>
  );
};

export default DetailLayout;
