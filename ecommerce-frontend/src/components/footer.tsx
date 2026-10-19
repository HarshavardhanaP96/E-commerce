import { brand } from "../tenant/tenant";
import { Link, useLocation } from "react-router-dom";
import { POLICIES, POLICY_LINKS, policyPath } from "../legal/policies";

/** Policy links on every store page (E-Commerce Rules 2020; Razorpay onboarding). Admin has its own shell. */
const Footer = () => {
  const { pathname } = useLocation();
  if (pathname.startsWith("/admin")) return null;

  return (
    <footer className="footer">
      <nav aria-label="Policies">
        <Link to="/help">Help Centre</Link>
        {POLICY_LINKS.map((slug) => (
          <Link key={slug} to={policyPath(slug)}>
            {POLICIES[slug].title}
          </Link>
        ))}
      </nav>
      <small>© {new Date().getFullYear()} {brand.name}. Payments secured by Razorpay.</small>
    </footer>
  );
};

export default Footer;
