# Security & Architecture Audit — 2026-10-14

This audit covers the Node/Express backend (`ecommerce-backend`, since removed), the React frontend, and the
deployment config. Each finding notes how the Java migration (`backend/`) or the frontend changes address it.

## Critical
| # | Finding | Where (old) | Fix |
|---|---|---|---|
| C1 | **Account takeover.** `POST /user/reset-password` set any user's password from just an email address, with no token. | `controllers/auth.ts` resetPassword | Two-step reset with a single-use, hashed token that expires in 15 minutes; responses don't reveal whether an email exists (FR2) |
| C2 | **Hard-coded JWT fallback secret `"secret"`.** The k8s manifests never set `JWT_SECRET`, so production tokens could be forged. | `middlewares/auth.ts`, `controllers/auth.ts`, `k8s/*/backend.yaml` | The app won't start without a `JWT_SECRET` of at least 32 bytes; the secret is wired into k8s; tokens last 24 h (ADR-006) |
| C3 | **Client-controlled prices.** `POST /order/new` trusted `subtotal`, `total`, `discount` and `user` from the request body, so anyone could order anything for ₹1, for any user. | `controllers/order.ts` | The server prices the cart; the user id comes from the JWT; orders are created only from a verified payment intent (ADR-009) |
| C4 | **Payment never tied to an order.** `/payment/verify` returned success, but nothing linked the payment to the order or to the amount paid. | `controllers/payment.ts` | Signature verification and order creation happen in one call against a stored intent |
| C5 | **Unauthenticated order endpoints.** `newOrder`, `myOrders` (`?id=` IDOR, so anyone could read anyone's orders) and `getOrderById` had no auth. | `routes/order.ts` | JWT required everywhere; owner-or-admin check on reading an order; `/my` uses the token's subject |

## High
| # | Finding | Fix |
|---|---|---|
| H1 | `adminOnly` was used without `isAuthenticated` on order and coupon routes, so those admin routes always returned 401 (broken). | Declarative `hasRole('ADMIN')` rules in `SecurityConfig` |
| H2 | **NoSQL injection.** `?coupon[$ne]=x` was passed straight into `Coupon.findOne`, and the search regex was unescaped (ReDoS). | Typed request params; `Pattern.quote` on search; input length limits |
| H3 | **Upload stored XSS.** No type or size limits, the file extension came from the client, and `.html` or `.svg` files were served from the same origin. | Type detected from magic bytes (jpeg/png/webp/gif), 5 MB limit, server-chosen extension, `nosniff` |
| H4 | `deleteProduct` called `Product.deleteOne()` with no filter, deleting **an arbitrary product**. It also deleted the photo using a path taken from the database. | Delete by id; file deletion is confined to the uploads root |
| H5 | Stock decrement ran in an un-awaited `forEach`, with no stock check (oversell, unhandled rejections). | Conditional atomic decrement with rollback (409 on conflict) |
| H6 | `seed.ts` **deletes all users** and creates `admin@gmail.com / adminpassword`. | `seed` profile creates the admin from env vars only if they're missing, and never deletes anything |
| H7 | Register sent multipart from the frontend but the backend parsed only JSON, so **registration always failed**. | Multipart register endpoint |
| H8 | Product admin calls from the frontend sent no `Authorization` header (a shared `baseQuery` was missing) → 401. | A single authed `baseQuery` shared by all RTK Query APIs |

## Medium
- M1: Login logged the email and whether the password matched. Fixed: structured logs with no credentials, plus a metric with the result.
- M2: No rate limiting on login. Fixed: per-IP limiter on `/user/login` and `/user/forgot-password`.
- M3: Responses could include the password hash if the model was loaded with `+password`. Fixed: DTOs never carry it.
- M4: Error middleware leaked messages from unexpected errors. Fixed: 500 returns a generic message, and details are logged with the traceId.
- M5: Swagger UI was public in production. Fixed: springdoc is disabled when `SPRINGDOC_ENABLED=false` (prod overlay).
- M6: The frontend Docker image baked `VITE_SERVER` in at build time. Fixed: same-origin nginx proxy (ADR-010), plus CSP and security headers.
- M7: Containers ran as root, with no probes, no resource limits and no PodDisruptionBudget. Fixed in the kustomize base.
- M8: User uploads (and an oddly named `…function pop() { [native code] }` file) were committed to git. Fixed: `uploads/` is gitignored.
- M9: Cart coupon validation used `Math.random()` (mock). Fixed: calls `/payment/discount`.

## Correctness bugs
- `sixMonthAgo.setDate(sixMonthAgo.getMonth() - 6)` set the *day*, not the month, so chart ranges were wrong. Month bucketing also ignored the year. Fixed in `MonthBuckets`.
- Response contract mismatches between frontend and backend: `totalpage` vs `totalPage`, `genderRatio` vs `userRatio`, `ageGroup` vs `usersAgeGroup`, `product` vs `products`. Unified in `contracts.md`.
- `processOrder` and `deleteOrder` returned "Product Not Found" for a missing order.
- `getInventories` divided by zero when there were no products.
- The admin pages, orders, order details and shipping were all hard-coded mock data. Now wired to the API.

## Architecture
- Module cycles `app.ts ↔ controllers` (via the `myCache` and `razorpay` singletons exported from the entry point; found by graphify) → replaced by DI with module ports.
- The in-process cache combined with 2 replicas meant pods served inconsistent data → short-TTL Caffeine now; Redis path documented (ADR-007).
- Local-disk uploads with 2 replicas meant images went missing on other pods → `FileStorage` port; RWX PVC now, S3 adapter next (ADR-008).
- `/admin` was a stale, gitignored duplicate frontend → left untouched (it isn't tracked); recommend deleting it locally.
