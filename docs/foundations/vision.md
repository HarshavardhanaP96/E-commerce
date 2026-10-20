# Vision & Product Requirements

## Outcome
6Pack is a small single-store e-commerce app: customers browse and search products, check out with
Razorpay, and track their orders. Admins manage products, customers, orders and coupons, and see
dashboard analytics. This phase moves the backend from Node/Express to a Java 21 / Spring Boot modular
monolith. It also closes the security holes found in the October 2026 audit (`docs/AUDIT.md`), and replaces
the mock data in the UI with real API calls.

## Scope
- In scope: a Java backend with the same `/api/v1` surface, with contract and security fixes; the frontend
  wired to real data; the UI fixes listed in `prompt.txt`; containerisation, k8s manifests, CI/CD, and a
  baseline for metrics, traces and structured logs. Later phases added richer entities, variants, auditing and
  multi-tenancy (see Status below).

## Non-goals
- Not now: splitting into microservices, product reviews, wishlists, inventory reservation timeouts, a real
  email provider (a mail port exists, and dev logs the reset link), or object storage for images (a storage
  port exists, and the local disk adapter ships).
- Multi-tenancy was a non-goal in the first phase and has since been built (ADR-020, ADR-021): one deployment
  serves several isolated stores. Self-serve signup and cross-tenant reporting are not part of it.

## Status and what is next
Built since the migration: richer catalogue, user, order and coupon models (ADR-015 to ADR-018), product variants
behind a flag (ADR-019), an audit log and an admin acceptable-use agreement, and multi-tenancy with a platform
administrator. The backlog below is what is still open, in the order it is best tackled. Details live in
`docs/superpowers/specs/` and `docs/superpowers/plans/`.

| Priority | Item | Why now / depends on |
|---|---|---|
| 1 | Ship what exists: click through the new screens in a browser, push and let CI run for the first time, merge the audit and variants branches once, split the unreviewed `wip` consent commit | Nothing built since the migration has been seen in a browser or run in CI; each day apart adds merge conflicts |
| 2 | Answer the open tenancy decisions in `requirements.md` (shared customers, per-tenant legal text, own payment accounts, scale, existing data) | Defaults were applied; changing them later is cheaper before a second real tenant exists |
| 3 | Product variants: storefront product page and cart identity, admin variant editor, then turn the flag on | Backend is done and tested with the flag off |
| 4 | ~~Tenant offboarding (export then delete), a cross-tenant audit view and audit of platform actions~~ Done on `feat/tenancy-hardening` (2026-10-20) | Needed before the first outside tenant |
| 5 | Real email provider (per-tenant sender) and object storage for uploads | Reset links only log today; local disk limits the backend to one replica (ADR-008) |
| 6 | Payment depth: payment method and failure reason, refunds and returns, shipments, invoices | Needs a gateway fetch-payment call or webhooks, so after per-tenant payment accounts are live |
| 7 | Smaller gaps: category and gallery admin screens, a suspend toggle for users, email verification flow, enforcing suspension on already-issued tokens, coupon scoping and first-order-only coupons, per-tenant policy text, custom-domain verification, end-to-end browser tests | Each is independent and small |

## Success criteria
- `mvn verify` passes: unit tests, Mongo Testcontainers integration tests, and the Spring Modulith boundary check.
- `npm run build` and `npm run lint` pass in `ecommerce-frontend`.
- `docker compose up` runs the full stack. A customer can register, log in, add to cart, check out with the
  fake or Razorpay gateway, and see the order. An admin can do CRUD on products, process orders, and view
  the dashboard.
- None of the critical or high audit findings can be reproduced.

## Constraints
- Keep MongoDB (database `Ecommerce_2024`) so existing data keeps working. Existing bcrypt hashes stay valid.
- Razorpay (INR) is the payment provider.
