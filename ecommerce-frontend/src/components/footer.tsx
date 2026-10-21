import { brand } from "../tenant/tenant";
import { Link, useLocation } from "react-router-dom";
import { BUSINESS, POLICIES, PolicySlug, isFilledIn, policyPath } from "../legal/policies";

const HELP: PolicySlug[] = ["contact", "shipping", "refunds"];
const LEGAL: PolicySlug[] = ["terms", "privacy", "cookies"];

/** Policy links on every store page (E-Commerce Rules 2020; Razorpay onboarding). Admin has its own shell. */
const Footer = () => {
  const { pathname } = useLocation();
  if (pathname.startsWith("/admin")) return null;

  // Only details the store has filled in; the built-in "[…]" placeholders are never shown.
  const business = [BUSINESS.address, BUSINESS.email, BUSINESS.phone].filter(isFilledIn);

  return (
    <footer className="footer">
      <div className="footer__groups">
        <nav aria-label="Shop">
          <h2>Shop</h2>
          <Link to="/search">All products</Link>
          <Link to="/cart">Cart</Link>
          <Link to="/orders">Your orders</Link>
        </nav>
        <nav aria-label="Help">
          <h2>Help</h2>
          <Link to="/help">Help Centre</Link>
          {HELP.map((slug) => (
            <Link key={slug} to={policyPath(slug)}>
              {POLICIES[slug].title}
            </Link>
          ))}
        </nav>
        <nav aria-label="Policies">
          <h2>Policies</h2>
          {LEGAL.map((slug) => (
            <Link key={slug} to={policyPath(slug)}>
              {POLICIES[slug].title}
            </Link>
          ))}
        </nav>
        {business.length > 0 && (
          <address>
            <h2>{isFilledIn(BUSINESS.legalName) ? BUSINESS.legalName : brand.name}</h2>
            {business.map((line) => (
              <span key={line}>{line}</span>
            ))}
          </address>
        )}
      </div>
      <small>© {new Date().getFullYear()} {brand.name}. Payments secured by Razorpay.</small>
    </footer>
  );
};

export default Footer;
