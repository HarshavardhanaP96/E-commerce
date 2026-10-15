import { BrowserRouter as Router, Route, Routes, Navigate } from "react-router-dom";
import { Suspense, lazy, useEffect } from "react";
import ProtectedRoute from "./components/protected-route";
import Loader from "./components/loader";
import Header from "./components/header";
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

// Admin Routes
const Products = lazy(() => import("./pages/admin/products"));
const Customers = lazy(() => import("./pages/admin/customers"));
const Transaction = lazy(() => import("./pages/admin/transaction"));

const Coupon = lazy(() => import("./pages/admin/apps/coupon"));


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
      <Header />
      <Toaster position="bottom-center" />

      {/* Header */}
      <Suspense fallback={<Loader />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/search" element={<Search />} />
          <Route path="/cart" element={<Cart />} />
          
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
            <Route path="/admin/customer" element={<Customers />} />
            <Route path="/admin/transaction" element={<Transaction />} />
            
            {/* Apps */}
            <Route path="/admin/app/coupon" element={<Coupon />} />


            {/* Management */}
            <Route path="/admin/product/new" element={<NewProduct />} />
            <Route path="/admin/product/:id" element={<ProductManagement />} />
            <Route
              path="/admin/transaction/:id"
              element={<TransactionManagement />}
            />
            <Route path="/admin/customer/:id" element={<CustomerManagement />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </Router>
  );
};

export default App;
