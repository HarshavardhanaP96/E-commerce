# Test Strategy & Rollout

## Test strategy
- Unit (JUnit 5, AssertJ, Mockito): pricing (`PriceCalculator`), month bucketing (`MonthBuckets`), percentage
  change, Razorpay signature verification, image type sniffing, order status transitions, and JWT issue/parse.
- Integration (`@SpringBootTest` + Testcontainers `mongo:7`, MockMvc): auth flows (register multipart, login,
  forgot/reset), authorisation matrix (public, user, admin, owner), product CRUD with upload, checkout →
  confirm with the `fake` gateway including out-of-stock rollback, coupon CRUD, a dashboard smoke test, catalogue fields and visibility
  (`ProductDetailsIntegrationTest`), and category CRUD with its tree rules (`CategoryIntegrationTest`).
  These tests need Docker. CI's `ubuntu-latest` has it.
- Architecture: `ModularityTests` (`ApplicationModules.of(SixPackApplication.class).verify()`) fails the build
  on illegal cross-module access, and writes module docs to `target/spring-modulith-docs`.
- Frontend: `tsc` + ESLint in CI. E2E with Playwright against `docker compose` is TBD; add it when the UI
  stabilises after this migration.

## Rollout plan
1. Merge the branch, which runs CI and deploys to `test-env` automatically from the branch push.
2. On test: run a smoke test (register, login, browse, checkout with Razorpay test keys, admin dashboard).
   Check that existing users can log in, which proves bcrypt compatibility.
3. Create the prod secret, which must include `JWT_SECRET`. Note that all existing 30-day Node-issued tokens
   become invalid, so users have to log in again. This is expected.
4. Merge to `main` to deploy to prod with a rolling update (`maxUnavailable: 0`). Readiness gates traffic.
5. Data: no migration needed. Existing products load with default catalogue fields; the new sparse unique
   indexes on `slug`/`sku` and the `categories` collection are created at startup.
   Earlier collections (`payment_intents`, `password_reset_tokens`) and their indexes
   are created at startup (`spring.data.mongodb.auto-index-creation=true` on annotated documents only).

## Rollback / monitoring
- Signals: 5xx rate, p95 latency, readiness failures, `sixpack.checkout.failed`, ERROR logs containing
  `paid_but_unfulfilled`.
- Revert with `kubectl rollout undo deployment/backend-deploy -n prod-env`. The data model is
  backward-compatible with the Node backend (only additive collections and an optional `payment` field), so
  the old image can be redeployed if it's ever needed.
