# Test Strategy & Rollout

## Test strategy
- Unit (JUnit 5, AssertJ, Mockito): pricing (`PriceCalculator`), month bucketing (`MonthBuckets`), percentage
  change, Razorpay signature verification, image type sniffing, order status transitions, and JWT issue/parse.
- Integration (`@SpringBootTest` + Testcontainers `mongo:7`, MockMvc): auth flows (register multipart, login,
  forgot/reset), authorisation matrix (public, user, admin, owner), product CRUD with upload, checkout →
  confirm with the `fake` gateway including out-of-stock rollback, coupon CRUD, a dashboard smoke test, coupon rules (`CouponDomainTest`, `CouponPersistenceIntegrationTest`, `CouponAdminIntegrationTest`, `CouponCheckoutIntegrationTest`), order and payment fields (`OrderDomainTest`, `PriceCalculatorTest` tax parity, `OrderDetailsPersistenceIntegrationTest`, `OrderCheckoutDetailsIntegrationTest`, `OrderHistoryIntegrationTest`, `OrderErasureIntegrationTest`), user profile, address book and suspension (`UserDomainTest`, `UserDetailsPersistenceIntegrationTest`, `UserProfileIntegrationTest`, `UserStatusIntegrationTest`), catalogue fields and visibility
  (`ProductDetailsIntegrationTest`), and category CRUD with its tree rules (`CategoryIntegrationTest`).
  Added with the loose-ends release: token revocation on suspend, reactivate, role and delete (`UserStatusIntegrationTest`),
  consent retention after erasure (`ConsentIntegrationTest`), coupon scope and the quote endpoint (`CouponDomainTest`,
  `CouponScopeIntegrationTest`), payment method and refunds with a spied gateway
  (`PaymentDetailsAndRefundIntegrationTest`), the Razorpay REST calls against a mock server (`RazorpayGatewayTest`),
  invoices (`InvoiceIntegrationTest`) and email verification with a mocked mail sender (`EmailVerificationIntegrationTest`).
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
5. Data: no migration needed. Existing coupons keep working as always-active flat coupons; the `coupon_redemptions` collection and its unique index are created at startup. Before deploying, check product tax rates: any product with a non-18% `taxRate` now changes its quote. Existing products load with default catalogue fields; the new sparse unique
   indexes on `slug`/`sku` and the `categories` collection are created at startup.
   Earlier collections (`payment_intents`, `password_reset_tokens`) and their indexes
   are created at startup (`spring.data.mongodb.auto-index-creation=true` on annotated documents only).
   The `audit_log` collection and its indexes (including the retention TTL) are created at startup. Audit retention is fixed at 365 days in `AuditDocument`. Set `LEGAL_ADMIN_AUP_VERSION` in each environment if you want to change the default. Existing admins are asked to accept the agreement on their next page load.

   Loose-ends release: no data migration. New optional fields (`coupons.productIds/categories/firstOrderOnly`,
   `orders.payment.method/refunds/refundedPaise/failureReason`, `consents.expireAt`) are absent on old documents and
   load as "none". Two new indexes are created at startup: the TTL index on `consents.expireAt` (it only affects
   rows that get an `expireAt`, which happens when an account is erased from now on) and the
   `email_verification_tokens` collection. Existing customers show `emailVerified: false` and see the
   verification banner until they ask for a link; prod has no mail provider yet, so they can't get one (see the
   deployment checklist). Set the `INVOICE_SELLER_*` values before customers open invoices. The frontend now calls
   `POST /order/quote` for coupon previews, so deploy backend and frontend together.

## Rollback / monitoring
- Signals: 5xx rate, p95 latency, readiness failures, `sixpack.checkout.failed`, ERROR logs containing
  `paid_but_unfulfilled`.
- Alert on `sixpack.audit.write{result="failure"}` increasing (rule `SixpackAuditWriteFailing` in `infra/observability/k8s/alerts.yaml`): audit entries are being dropped. Changing the audit retention needs a code change in `AuditDocument` and, on a database that already has the `at` TTL index, that index dropped first (`db.audit_log.dropIndex("at")`), because MongoDB refuses to change an index's options in place.
- Revert with `kubectl rollout undo deployment/backend-deploy -n prod-env`. The data model is
  backward-compatible with the Node backend (only additive collections and an optional `payment` field).
- Rolling back past the admin agreement and audit trail is different: once any admin has accepted the agreement,
  `consents` holds `purpose: "admin_aup"` rows, and the previous build's `Purpose.fromWire` throws on them (HTTP 500 on
  `/consent/me` and checkout for those accounts). Either roll forward, or, before redeploying the old image, run
  `db.consents.deleteMany({purpose: "admin_aup"})`. Admins then accept the agreement again on the next deploy.
- Rolling back past the loose-ends release: the previous build ignores the new fields, but its order updates
  re-save the whole `payment` object and drop `refunds`, `refundedPaise` and `method` from any order it touches
  (moving an order to Shipped, for example). Refund records live in the Razorpay dashboard as well, so nothing is
  lost for good; still, prefer rolling forward once any refund has been issued. Scoped coupons would behave as
  unscoped coupons on the old build, so pause them first. Suspended users' old tokens work again on the old build.
- Deploy the frontend first or together with the backend. An old frontend served against the new backend would
  show an admin the agreement prompt without a link to the agreement text.
