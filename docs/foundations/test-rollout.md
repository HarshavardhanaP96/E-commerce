# Test Strategy & Rollout

## Test strategy
- Unit (JUnit 5, AssertJ, Mockito): pricing (`PriceCalculator`), month bucketing (`MonthBuckets`), percentage
  change, Razorpay signature verification, image type sniffing, order status transitions, and JWT issue/parse.
- Integration (`@SpringBootTest` + Testcontainers `mongo:7`, MockMvc): auth flows (register multipart, login,
  forgot/reset), authorisation matrix (public, user, admin, owner), product CRUD with upload, checkout →
  confirm with the `fake` gateway including out-of-stock rollback, coupon CRUD, a dashboard smoke test, coupon rules (`CouponDomainTest`, `CouponPersistenceIntegrationTest`, `CouponAdminIntegrationTest`, `CouponCheckoutIntegrationTest`), order and payment fields (`OrderDomainTest`, `PriceCalculatorTest` tax parity, `OrderDetailsPersistenceIntegrationTest`, `OrderCheckoutDetailsIntegrationTest`, `OrderHistoryIntegrationTest`, `OrderErasureIntegrationTest`), user profile, address book and suspension (`UserDomainTest`, `UserDetailsPersistenceIntegrationTest`, `UserProfileIntegrationTest`, `UserStatusIntegrationTest`), catalogue fields and visibility
  (`ProductDetailsIntegrationTest`), and category CRUD with its tree rules (`CategoryIntegrationTest`).
  These tests need Docker. CI's `ubuntu-latest` has it.
- Tenant isolation matrix: `TenantCatalogueIsolationIntegrationTest`, `TenantCommerceIsolationIntegrationTest`, `TenantIdentityIsolationIntegrationTest` (two tenants, same shapes, every entity), `TenantConfigurationIntegrationTest`, `PlatformAdminIntegrationTest`, and the rehearsal `LegacyMigrationIntegrationTest` (a single-tenant database upgraded in place).
- Tenancy: `TenantContextTest`, `TenantMongoIntegrationTest` (two tenants, same shapes), `TenantBootstrapIntegrationTest`, `TenantResolutionIntegrationTest`, `TenantTokenIntegrationTest`, and `TenantIsolationArchitectureTest` (no raw `MongoTemplate` outside the wrapper, with a shrinking allowlist). Tests that need a second tenant extend `TenantIntegrationTest`.
- Architecture: `ModularityTests` (`ApplicationModules.of(SixPackApplication.class).verify()`) fails the build
  on illegal cross-module access, and writes module docs to `target/spring-modulith-docs`.
- Frontend: `tsc` + ESLint in CI. E2E with Playwright against `docker compose` is TBD; add it when the UI
  stabilises after this migration.

## Multi-tenancy rollout (run before the first deploy that contains it)
1. Back up the database. Restore the backup into a scratch database and start the new build against it (`MONGO_DB=<scratch>`).
2. At startup it creates the `default` tenant, assigns every existing document to it (`TenantBackfill`) and drops the old
   global unique indexes (`LegacyIndexCleanup`). The log lines say how many documents and which indexes. A second start must
   report zero of both.
3. Verify against the scratch copy: a document count per collection before and after; none without `tenantId`
   (`db.<collection>.countDocuments({tenantId:{$exists:false}})` is 0 for users, products, categories, coupons,
   coupon_redemptions, orders, payment_intents, password_reset_tokens, consents, audit_log); a known customer can log in; the
   catalogue, an old order and a coupon show up; checkout works.
4. Environment: nothing is required for a single store (with one tenant every host resolves to it). Before creating a
   second tenant set `TENANT_DEFAULT_DOMAINS` to the current production hosts, add `PLATFORM_ADMIN_EMAIL` and
   `PLATFORM_ADMIN_PASSWORD`, and add each tenant's `TENANT_SECRET_<REF>` to the sealed secret.
5. Deploy. The tenant is chosen from the request host (`X-Forwarded-Host` when present), so the ingress must set that header
   itself and not pass on a value sent by the client (ingress-nginx does this by default). Tokens issued before the deploy keep
   working on the default tenant until they expire (24 h).
6. Rollback: redeploy the previous image. It ignores `tenantId` and the new indexes. The old global unique indexes are not
   recreated by it, which is harmless while only the default tenant exists. Roll back **before** creating a second tenant:
   two tenants' data cannot live under the old single-tenant constraints.

## Rollout plan
1. Merge the branch, which runs CI and deploys to `test-env` automatically from the branch push.
2. On test: run a smoke test (register, login, browse, checkout with Razorpay test keys, admin dashboard).
   Check that existing users can log in, which proves bcrypt compatibility.
3. Create the prod secret, which must include `JWT_SECRET`. Note that all existing 30-day Node-issued tokens
   become invalid, so users have to log in again. This is expected.
4. Merge to `main` to deploy to prod with a rolling update (`maxUnavailable: 0`). Readiness gates traffic.
5. Data: no migration needed. The `tenants` collection and the `default` tenant are created at startup. With one tenant every host resolves to it, so no configuration is required; set `TENANT_DEFAULT_DOMAINS` before a second tenant is created. Tokens issued before this release stay valid on the default tenant until they expire. Existing coupons keep working as always-active flat coupons; the `coupon_redemptions` collection and its unique index are created at startup. Before deploying, check product tax rates: any product with a non-18% `taxRate` now changes its quote. Existing products load with default catalogue fields; the new sparse unique
   indexes on `slug`/`sku` and the `categories` collection are created at startup.
   Earlier collections (`payment_intents`, `password_reset_tokens`) and their indexes
   are created at startup (`spring.data.mongodb.auto-index-creation=true` on annotated documents only).
   The `audit_log` collection and its indexes (including the retention TTL) are created at startup. Audit retention is fixed at 365 days in `AuditDocument`. Set `LEGAL_ADMIN_AUP_VERSION` in each environment if you want to change the default. Existing admins are asked to accept the agreement on their next page load.

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
- Deploy the frontend first or together with the backend. An old frontend served against the new backend would
  show an admin the agreement prompt without a link to the agreement text.
