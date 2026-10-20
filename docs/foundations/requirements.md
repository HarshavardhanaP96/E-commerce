# Requirements & Non-Functional Requirements

## Functional requirements
- [ ] FR1: Register accepts multipart (name, email, password, gender, dob, optional photo). Login returns a JWT and the user.
- [ ] FR2: Forgot password is two steps. A request emails a single-use token that expires after 15 minutes. Reset takes token + new password.
- [ ] FR3: Product catalogue: latest 5, distinct categories, search (name, max price, category, sort, page), and details.
- [ ] FR4: Admin product CRUD with an image upload (jpeg, png, webp or gif; max 5 MB).
- [ ] FR4a: Products carry optional catalogue fields (slug, sku, description, brand, mrp, tax rate, HSN, status, tags, gallery images (up to 5, uploaded from the product page), category link). Older products without them keep working. Draft and archived products are hidden from the storefront and can't be bought.
- [ ] FR4c: A product can have variants along up to 3 option axes (size, colour, material, capacity, pack size, any custom axis), up to 100 per product. Each variant has its own SKU, price, optional MRP, stock and status. Product `price` and `stock` are derived from them. Admin edits of the variant structure never overwrite stock that changed in the meantime. Products without variants behave as before. Customers pick options on a product page; the cart and orders keep the chosen variant. Admins edit variants on the product's Variants tab. `app.products.variants-enabled` can switch creation off. Plan: `docs/superpowers/plans/2026-10-18-product-variants.md`.
- [ ] FR4d: A product can carry a size guide (a table of up to 8 columns and 30 rows plus a note) shown on its page, and a low-stock threshold (store default 5, `PRODUCT_LOW_STOCK_THRESHOLD`). Admins see a low-stock list (product or active variant at or below its threshold), per-variant sales on the Variants tab, and can bulk-edit price, MRP, stock, status and threshold through a CSV export/import that is previewed first and refused as a whole if any row is invalid.
- [ ] FR4b: Admin category CRUD, with a Categories screen in the admin sidebar (create, edit, image, parent, delete) and a category link on the product page, with parent/child nesting. A category with sub-categories or products can't be deleted, and a parent can't create a cycle.
- [ ] FR5: Checkout. The server prices the cart from the database, applies the coupon, and adds tax at each product's rate (18% by default) and shipping (₹200 below ₹1000). It then creates a Razorpay order. Confirming checkout verifies the signature, decrements stock atomically, and creates the order.
- [ ] FR6: Customers see only their own orders. Admins see all orders, can move an order through Processing → Shipped → Delivered, and can delete orders.
- [ ] FR6a: Orders carry an order number (`ORD-YYYYMMDD-XXXXXX`), the recipient's name and phone, an optional billing address, delivery notes, and a status history that records who moved the order and when.
- [ ] FR7: Admin coupon management: flat or percent, optional cap, minimum order, validity window, total and per-customer limits, pause/resume, and a status and usage count per coupon. A coupon can be limited to chosen products or categories (it then discounts only those lines) and to a customer's first order. Customers see why a code was refused, and the cart shows the discount checkout will charge.
- [ ] FR8: Admin customer list, details, role change, and delete. An admin cannot delete themselves.
- [ ] FR8a: Customers can edit name, phone and locale. Admins can suspend and reactivate accounts (never their own), from the customer page. A suspended user can't log in, and the token they already hold stops working at once on every endpoint (403); a deleted account's token gets 401.
- [ ] FR8b: Customers keep up to 10 saved addresses with one default. Checkout pre-selects the default and lets them pick another. The data export includes the addresses.
- [ ] FR9: Admin dashboard: stats, pie, bar and line charts, optionally scoped by productId, category or userId.
- [ ] FR10: Admin tables have filters inline with search, with pagination at the bottom (`prompt.txt`).
- [ ] FR11: Detail pages (product, user, order) have Save and Delete at the top right and fit the viewport without page scroll.
- [ ] FR12: Admins must accept an acceptable-use agreement before using admin endpoints, and again when its version changes.
- [ ] FR14: Admins can refund an order, in full or in parts, to the original payment method through the gateway. The customer sees each refund and its reason. Two refunds can't pay out the same money, and a failed refund can be retried safely. Orders record how they were paid.
- [ ] FR15: Customers and admins can open a printable invoice for an order, with the seller's details, the tax split by rate (CGST+SGST in the seller's state, IGST elsewhere), and any refunds.
- [ ] FR16: Registration emails a single-use verification link (valid 24 h); following it marks the email verified. Customers can ask for a new link from a banner. Nothing is blocked while an email is unverified.
- [ ] FR13: Data changes (products, categories, coupons, orders, users) and security events (logins, password resets, consent changes) are recorded in an append-only audit log that admins can search, without storing personal values.

## Non-functional requirements
- Performance: p95 under 200 ms for catalogue reads at 50 rps on one pod. Catalogue reads and dashboard queries are cached (Caffeine, short TTL).
- Security: every write and every non-catalogue read needs a JWT. Admin routes need `ROLE_ADMIN`. `JWT_SECRET` must be ≥ 32 bytes or startup fails. Passwords are bcrypt. Login is rate-limited. Uploads are checked by magic bytes and served with `nosniff`. Regex search input is escaped. Clients never send prices or totals. CORS is restricted to `CLIENT_URL`. Containers run as non-root.
- Scalability: the backend is stateless apart from the in-process cache and local uploads (see ADR-007/008 for the multi-replica path).
- Observability: `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`, OTLP traces, and JSON (ECS) logs carrying trace ids.
- Tenant lifecycle: platform administrators create, edit, suspend and reactivate tenants (domains, theme, legal versions, payment account reference, email link base, storefront details); a store is rendered from its own config; a suspended store is unavailable.
- Tenancy: tenant-owned data is reachable only through `TenantMongo` (enforced by `TenantIsolationArchitectureTest`, with a shrinking allowlist during the migration). A request resolves to exactly one tenant from its host, and an access token is valid only for the tenant that issued it.
- Compliance: DPDP Act 2023 + Rules 2025, Consumer Protection (E-Commerce) Rules 2020, dark-pattern guidelines, and Razorpay's required policy pages. See `compliance-consent.md` for the policies, consent points, cookie inventory and handling. Razorpay keeps card data; we store only order and payment ids.

## Open questions / edge cases
- Concurrent checkouts on the last unit: handled by a conditional decrement (`stock >= qty`). The loser gets 409, and earlier decrements in the same order are rolled back.
- A payment that was captured but whose order failed (e.g. out of stock after payment) is logged at ERROR with the payment id so an admin can refund it from the order page or the Razorpay dashboard (if the order was never created there is no order page, so use the dashboard). Refunds are never automatic.
- Product deletion keeps existing orders intact, because orders snapshot name, photo and price.

## Decisions needed (applied by default, not yet confirmed by the owner)
These were left open in `docs/superpowers/specs/2026-10-18-multitenancy-design.md` §6 and built on the recommended
default. Each is cheaper to change before a second real tenant exists.
- **Customers per tenant (Q1):** separate accounts per store; the same email is a different person in each. The
  alternative is one login across stores with a membership per tenant.
- **Legal text (Q4):** policy versions and business details are per tenant, but the policy text is shared and
  rebuilt with the store's name and details. Fully custom text per tenant is not built.
- **Payments (Q5):** each tenant brings its own Razorpay account. A platform-collects-for-merchants model (Razorpay
  Route, settlements) is a much larger piece of work.
- **Scale and onboarding (Q7):** a handful of tenants created by a platform administrator. Self-serve signup, billing
  and domain verification are not built.
- **Existing data (Q14):** today's data becomes the `default` tenant. Confirm there is no data that should be split
  or archived instead.
- **Still open:** cross-tenant platform reporting (Q8); dedicated databases for regulated tenants (Q9); a procedure for
  tenant offboarding, i.e. export then delete (Q13); whether the platform administrator must accept the admin agreement
  (Q16); whether the platform may see every tenant's audit log (Q17); sender address per tenant when a mail provider
  arrives (Q11); wildcard or custom-domain support (Q12).

## Known limits (accepted for now)
- **Suspension is not instant:** a suspended user is refused at login and `/me`, but a token already issued keeps
  working until it expires (24 h). Enforcing it per request costs a database read per call.
- **Coupons can be over-used** by the number of checkouts open at the same moment, and deleting an order does not
  return a use (ADR-018).
- **Product variants are off** (`app.products.variants-enabled=false`) until the storefront and admin editor exist.
- **`emailVerified` is stored but nothing sets it**; there is no verification flow.
- **Platform administrator actions** are logged but not written to the tenant-scoped audit log.
- **The tenant is chosen from the request host**, so the ingress must overwrite any client-supplied
  `X-Forwarded-Host`, and a second tenant needs `TENANT_DEFAULT_DOMAINS` set first.
- **Payment method and failure reason** are not recorded; a captured payment whose order failed is still refunded by hand.
