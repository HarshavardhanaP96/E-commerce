import { BrowserRouter as Router, Route, Routes, Navigate, useLocation } from "react-router-dom";
import { Suspense, lazy, useEffect } from "react";
import ProtectedRoute from "./components/protected-route";
import Loader from "./components/loader";
import Header from "./components/header";
import BottomNav from "./components/bottomNav";
import Footer from "./components/footer";
import ConsentPrompt from "./components/consentPrompt";
import VerifyEmailBanner from "./components/verifyEmailBanner";
import { Toaster } from "react-hot-toast";
import { useDispatch, useSelector } from "react-redux";
import { userExist, userNotExist } from "./redux/reducer/userReducer";
import { useLazyGetMyProfileQuery } from "./redux/api/userAPI";
import { getToken } from "./redux/api/baseQuery";
import { RootState } from "./redux/store";

const Home = lazy(() => import("./pages/home"));
const Search = lazy(() => import("./pages/search"));
const Cart = lazy(() => import("./pages/cart"));
const Shipping = lazy(() => import("./pages/shipping"));
const Login = lazy(() => import("./pages/login"));
const ForgotPassword = lazy(() => import("./pages/forgot-password"));
const Orders = lazy(() => import("./pages/orders"));
const OrderDetails = lazy(() => import("./pages/order-details"));
const InvoicePage = lazy(() => import("./pages/invoice"));
const Legal = lazy(() => import("./pages/legal"));
const VerifyEmail = lazy(() => import("./pages/verify-email"));
const Help = lazy(() => import("./pages/help"));
const AdminHelp = lazy(() => import("./pages/admin/helpcenter"));
const PrivacySettings = lazy(() => import("./pages/account/privacy"));
const Addresses = lazy(() => import("./pages/account/addresses"));

// Admin Routes
const Products = lazy(() => import("./pages/admin/products"));
const Customers = lazy(() => import("./pages/admin/customers"));
const Transaction = lazy(() => import("./pages/admin/transaction"));

const Coupon = lazy(() => import("./pages/admin/apps/coupon"));
const CategoryAdmin = lazy(() => import("./pages/admin/category"));
const AuditLog = lazy(() => import("./pages/admin/audit"));


const Analytics = lazy(() => import("./pages/admin/analytics"));
const NewProduct = lazy(() => import("./pages/admin/management/newproduct"));
const ProductManagement = lazy(
  () => import("./pages/admin/management/productmanagement")
);
const TransactionManagement = lazy(
  () => import("./pages/admin/management/transactionmanagement")
);
const CustomerManagement = lazy(
  () => import("./pages/admin/management/customermanagement")
);

/** Tags <html> with the active surface so the store / admin token sets in _tokens.scss apply. */
const SurfaceScope = () => {
  const { pathname } = useLocation();
  useEffect(() => {
    document.documentElement.dataset.surface = pathname.startsWith("/admin") ? "admin" : "store";
  }, [pathname]);
  return null;
};

const App = () => {
  const dispatch = useDispatch();
  const { user, loading } = useSelector((state: RootState) => state.userReducer);
  const [fetchProfile] = useLazyGetMyProfileQuery();

  // Restore the session from a stored token; an invalid/expired token signs the user out.
  useEffect(() => {
    if (!getToken()) {
      dispatch(userNotExist());
      return;
    }
    fetchProfile()
      .unwrap()
      .then((data) => dispatch(userExist(data.user)))
      .catch(() => dispatch(userNotExist()));
  }, [dispatch, fetchProfile]);

  if (loading) return <Loader />;

  return (
    <Router>
      <SurfaceScope />
      <Header />
      <Toaster
        position="bottom-center"
        // Lifted above the mobile bottom nav when it's shown (see .bottom-nav in app.scss).
        containerStyle={{ bottom: "var(--toast-bottom, 16px)" }}
        toastOptions={{
          style: {
            fontFamily: "var(--font-sans)",
            fontSize: "var(--fs-body-sm)",
            color: "var(--text)",
            borderRadius: "var(--radius-md)",
            border: "1px solid var(--border)",
            boxShadow: "var(--shadow-md)",
          },
        }}
      />

      {user && user.emailVerified === false && <VerifyEmailBanner key={user._id} />}

      {/* .app-main is a plain block that grows, so the footer sits at the bottom of short pages */}
      <div className="app-main">
      <Suspense fallback={<Loader />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/search" element={<Search />} />
          <Route path="/cart" element={<Cart />} />
          <Route path="/legal/:slug" element={<Legal />} />
          <Route path="/help" element={<Help />} />
          <Route path="/verify-email" element={<VerifyEmail />} />

          {/* only for not logged in */}
          <Route
            path="/login"
            element={
              <ProtectedRoute isAuthenticated={user ? false : true} redirect="/">
                <Login />
              </ProtectedRoute>
            }
          />
           <Route
             path="/forgot-password"
             element={
               <ProtectedRoute isAuthenticated={user ? false : true} redirect="/">
                 <ForgotPassword />
               </ProtectedRoute>
             }
           />
          
          {/* logged in user routes */}
          <Route
            element={<ProtectedRoute isAuthenticated={user ? true : false} />}
          >
            <Route path="/shipping" element={<Shipping />} />
            <Route path="/orders" element={<Orders />} />
            <Route path="/order/:id" element={<OrderDetails />} />
            <Route path="/order/:id/invoice" element={<InvoicePage />} />
            <Route path="/account/privacy" element={<PrivacySettings />} />
            <Route path="/account/addresses" element={<Addresses />} />
          </Route>
          
          {/* Admin Routes */}
          <Route
            element={
              <ProtectedRoute
                isAuthenticated={user ? true : false}
                adminOnly={true}
                admin={user?.role === "admin" ? true : false}
              />
            }
          >
             <Route path="/admin" element={<Navigate to="/admin/analytics" replace />} />
            
            <Route path="/admin/analytics" element={<Analytics />} />
            <Route path="/admin/product" element={<Products />} />
            <Route path="/admin/category" element={<CategoryAdmin />} />
            <Route path="/admin/customer" element={<Customers />} />
            <Route path="/admin/transaction" element={<Transaction />} />
            
            {/* Apps */}
            <Route path="/admin/app/coupon" element={<Coupon />} />
            <Route path="/admin/audit" element={<AuditLog />} />


            {/* Management */}
            <Route path="/admin/product/new" element={<NewProduct />} />
            <Route path="/admin/product/:id" element={<ProductManagement />} />
            <Route
              path="/admin/transaction/:id"
              element={<TransactionManagement />}
            />
            <Route path="/admin/customer/:id" element={<CustomerManagement />} />
            <Route path="/admin/help" element={<AdminHelp />} />
            <Route path="/admin/help/:slug" element={<AdminHelp />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
      </div>
      {user && <ConsentPrompt key={user._id} userId={user._id} />}
      <Footer />
      <BottomNav />
    </Router>
  );
};

export default App;
