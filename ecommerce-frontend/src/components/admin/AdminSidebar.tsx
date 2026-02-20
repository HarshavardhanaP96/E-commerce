import { useEffect, useState } from "react";
import { AiFillFileText } from "react-icons/ai";
import {

} from "react-icons/fa";
import { HiMenuAlt4 } from "react-icons/hi";
import { IoIosPeople } from "react-icons/io";
import {
  RiCoupon3Fill,
  RiDashboardFill,
  RiShoppingBag3Fill,
} from "react-icons/ri";
import { Link, Location, useLocation } from "react-router-dom";
import { IconType } from "react-icons";

const AdminSidebar = () => {
  const location = useLocation();

  const [showModal, setShowModal] = useState<boolean>(false);
  const [phoneActive, setPhoneActive] = useState<boolean>(
    window.innerWidth < 1100
  );

  const resizeHandler = () => {
    setPhoneActive(window.innerWidth < 1100);
  };

  useEffect(() => {
    window.addEventListener("resize", resizeHandler);

    return () => {
      window.removeEventListener("resize", resizeHandler);
    };
  }, []);

  return (
    <>
      {phoneActive && (
        <button id="hamburger" onClick={() => setShowModal(true)}>
          <HiMenuAlt4 />
        </button>
      )}

      <aside
        style={
          phoneActive
            ? {
                width: "20rem",
                height: "100vh",
                position: "fixed",
                top: 0,
                left: showModal ? "0" : "-20rem",
                transition: "all 0.5s",
              }
            : {}
        }
      >
        <h2>Logo.</h2>
        
        {/* Unified Main Navigation */}
        <div>
          <h5>Menu</h5>
          <ul>
             <Li
              url="/admin/analytics"
              text="Analytics"
              Icon={RiDashboardFill}
              location={location}
            />
            <Li
              url="/admin/product"
              text="Product"
              Icon={RiShoppingBag3Fill}
              location={location}
            />
            <Li
              url="/admin/customer"
              text="Customer"
              Icon={IoIosPeople}
              location={location}
            />
            <Li
              url="/admin/transaction"
              text="Transaction"
              Icon={AiFillFileText}
              location={location}
            />
            <Li
              url="/admin/app/coupon"
              text="Coupon"
              Icon={RiCoupon3Fill}
              location={location}
            />

          </ul>
        </div>

        {phoneActive && (
          <button id="close-sidebar" onClick={() => setShowModal(false)}>
            Close
          </button>
        )}
      </aside>
    </>
  );
};

interface LiProps {
  url: string;
  text: string;
  location: Location;
  Icon: IconType;
}
const Li = ({ url, text, location, Icon }: LiProps) => (
  <li
    style={{
      backgroundColor: location.pathname.includes(url)
        ? "rgba(0,115,255,0.1)"
        : "white",
    }}
  >
    <Link
      to={url}
      style={{
        color: location.pathname.includes(url) ? "rgb(0,115,255)" : "black",
      }}
    >
      <Icon />
      {text}
    </Link>
  </li>
);

export default AdminSidebar;
