# Requirements & Non-Functional Requirements

## Functional requirements
- [ ] FR1: Register accepts multipart (name, email, password, gender, dob, optional photo). Login returns a JWT and the user.
- [ ] FR2: Forgot password is two steps. A request emails a single-use token that expires after 15 minutes. Reset takes token + new password.
- [ ] FR3: Product catalogue: latest 5, distinct categories, search (name, max price, category, sort, page), and details.
- [ ] FR4: Admin product CRUD with an image upload (jpeg, png, webp or gif; max 5 MB).
- [ ] FR4a: Products carry optional catalogue fields (slug, sku, description, brand, mrp, tax rate, HSN, status, tags, gallery images, category link). Older products without them keep working. Draft and archived products are hidden from the storefront and can't be bought.
- [ ] FR4c: A product can have variants along up to 3 option axes (size, colour, material, capacity, pack size, any custom axis), up to 100 per product. Each variant has its own SKU, price, optional MRP, stock and status. Product `price` and `stock` are derived from them. Admin edits of the variant structure never overwrite stock that changed in the meantime. Products without variants behave as before. *Phase 1 (admin API) is built behind `app.products.variants-enabled`; checkout, orders, storefront and the admin editor follow in `docs/superpowers/plans/2026-10-18-product-variants.md`.*
- [ ] FR4b: Admin category CRUD with parent/child nesting. A category with sub-categories or products can't be deleted, and a parent can't create a cycle.
- [ ] FR5: Checkout. The server prices the cart from the database, applies the coupon, and adds tax at each product's rate (18% by default) and shipping (₹200 below ₹1000). It then creates a Razorpay order. Confirming checkout verifies the signature, decrements stock atomically, and creates the order.
- [ ] FR6: Customers see only their own orders. Admins see all orders, can move an order through Processing → Shipped → Delivered, and can delete orders.
- [ ] FR6a: Orders carry an order number (`ORD-YYYYMMDD-XXXXXX`), the recipient's name and phone, an optional billing address, delivery notes, and a status history that records who moved the order and when.
- [ ] FR7: Admin coupon management: flat or percent, optional cap, minimum order, validity window, total and per-customer limits, pause/resume, and a status and usage count per coupon. Customers see why a code was refused.
- [ ] FR8: Admin customer list, details, role change, and delete. An admin cannot delete themselves.
- [ ] FR8a: Customers can edit name, phone and locale. Admins can suspend and reactivate accounts (never their own). A suspended user can't log in or load `/me`.
- [ ] FR8b: Customers keep up to 10 saved addresses with one default. Checkout pre-selects the default and lets them pick another. The data export includes the addresses.
- [ ] FR9: Admin dashboard: stats, pie, bar and line charts, optionally scoped by productId, category or userId.
- [ ] FR10: Admin tables have filters inline with search, with pagination at the bottom (`prompt.txt`).
- [ ] FR11: Detail pages (product, user, order) have Save and Delete at the top right and fit the viewport without page scroll.
- [ ] FR12: Admins must accept an acceptable-use agreement before using admin endpoints, and again when its version changes.
- [ ] FR13: Data changes (products, categories, coupons, orders, users) and security events (logins, password resets, consent changes) are recorded in an append-only audit log that admins can search, without storing personal values.

## Non-functional requirements
- Performance: p95 under 200 ms for catalogue reads at 50 rps on one pod. Catalogue reads and dashboard queries are cached (Caffeine, short TTL).
- Security: every write and every non-catalogue read needs a JWT. Admin routes need `ROLE_ADMIN`. `JWT_SECRET` must be ≥ 32 bytes or startup fails. Passwords are bcrypt. Login is rate-limited. Uploads are checked by magic bytes and served with `nosniff`. Regex search input is escaped. Clients never send prices or totals. CORS is restricted to `CLIENT_URL`. Containers run as non-root.
- Scalability: the backend is stateless apart from the in-process cache and local uploads (see ADR-007/008 for the multi-replica path).
- Observability: `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`, OTLP traces, and JSON (ECS) logs carrying trace ids.
- Compliance: DPDP Act 2023 + Rules 2025, Consumer Protection (E-Commerce) Rules 2020, dark-pattern guidelines, and Razorpay's required policy pages. See `compliance-consent.md` for the policies, consent points, cookie inventory and handling. Razorpay keeps card data; we store only order and payment ids.

## Open questions / edge cases
- Concurrent checkouts on the last unit: handled by a conditional decrement (`stock >= qty`). The loser gets 409, and earlier decrements in the same order are rolled back.
- A payment that was captured but whose order failed (e.g. out of stock after payment) is logged at ERROR with the payment id so it can be refunded by hand. Automated refunds come later.
- Product deletion keeps existing orders intact, because orders snapshot name, photo and price.
