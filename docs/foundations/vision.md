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
  baseline for metrics, traces and structured logs.

## Non-goals
- Not now: splitting into microservices, multi-tenant or multi-store, product reviews, wishlists,
  inventory reservation timeouts, a real email provider (a mail port exists, and dev logs the reset link),
  or object storage for images (a storage port exists, and the local disk adapter ships).

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
