import { ReactElement } from "react";
import { Navigate, Outlet } from "react-router-dom";

interface Props {
  children?: ReactElement;
  isAuthenticated: boolean;
  adminOnly?: boolean;
  admin?: boolean;
  redirect?: string;
}

const ProtectedRoute = ({
  isAuthenticated,
  children,
  adminOnly,
  admin,
  redirect = "/login",
}: Props) => {
  if (!isAuthenticated) return <Navigate to={redirect} />;

  if (adminOnly && !admin) return <Navigate to="/" />;

  return children ? children : <Outlet />;
};

export default ProtectedRoute;
