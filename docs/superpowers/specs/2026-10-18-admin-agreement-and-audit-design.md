# Admin acceptable-use agreement and the audit trail — design

Status: **draft for review.** Date: 2026-10-18. Branch: `feat/multi-tenancy` (work is independent of tenancy; see §7).

## 1. Intent

**Request:** "Add users policy agreements for initial login. Also add auditing of the data."

**What exists already (ADR-014).** Customers accept Terms and Privacy with a required checkbox at registration. A blocking
`ConsentPrompt` dialog re-asks on login while `/consent/me` returns a non-empty `pending` list (new policy version, or an account that
has no records yet). Checkout is refused server-side until the current Terms are accepted. Everything lands in the append-only
`consents` ledger with the version, a hashed IP and the user agent.

**What is missing.**
1. The **admin acceptable-use agreement** (P8 / C12 in `compliance-consent.md`, still unchecked). Admins are created by `AdminBootstrap`,
   skip the registration form, and nothing asks them to agree to rules for handling customer data.
2. Any **audit trail**. Nothing records who changed what. `docs/AUDIT.md` is a one-off security review, not a log.

**Decisions made with the user**
- Audit scope: **data changes plus authentication and security events.** Reads of personal data are out of scope.
- Capture by an **explicit `AuditTrail` port** called from services (not domain events, not persistence callbacks).
- The admin agreement is **enforced on the server**, not only by a dialog.

**Success looks like**
- An admin cannot use any admin endpoint until they have accepted the current acceptable-use version, and is asked again whenever the
  version changes.
- An admin can answer "who changed this product/order/coupon/user, when, from what to what" and "who failed to log in as X", from one
  place, and the log cannot be edited through the application.
- The log does not become a second copy of personal data and does not undermine erasure.

**Non-goals:** logging reads, tamper-proofing against someone with database access (hash chain), SIEM export, per-tenant audit views
(only the field is reserved), an audit log of the audit page itself, and recording system-driven stock decrements at checkout.

## 2. Part A — admin acceptable-use agreement

Reuse the consent ledger and `ConsentPrompt`. No second mechanism.

| Piece | Change |
|---|---|
| `Purpose` | Add `ADMIN_AUP` (wire value `admin_aup`). Required **only for the ADMIN role**: `Purpose.requiredFor(Role)` replaces the role-blind `required()` for deciding `pending`. Admins still also owe Terms and Privacy, which they never accepted, so their first login shows all three in one dialog. |
| Versions | `app.legal.admin-aup-version` (env `LEGAL_ADMIN_AUP_VERSION`, default `2026-10-18`). `ConsentService.currentVersion` returns it for `ADMIN_AUP`. `GET /legal/versions` lists it (public, harmless). |
| `ConsentService.state(userId, isAdmin)` | `consent` cannot call `user` (user already depends on consent). The caller passes the role it already has from the JWT (`AuthUser.admin()`). A user promoted to admin gets the claim, and so the prompt, on their next login. |
| `decide` | `admin_aup` can be granted only by an admin (400 otherwise) and, being required, cannot be withdrawn. |
| Enforcement | A `HandlerInterceptor` in `consent.web`, registered for `/api/v1/**`. For a JWT with role ADMIN it calls `hasAcceptedCurrent(ADMIN_AUP)`; if false it throws `ApiException.forbidden("Please accept the admin acceptable-use agreement")`. **Exempt:** `/api/v1/user/me/**`, `/api/v1/consent/**`, `/api/v1/legal/**`, so the dialog can load and be answered. It runs after authentication, so it sees the verified role. Cost: one indexed read per admin request, acceptable at admin traffic. Note it also covers an admin acting as a shopper; their dialog blocks the UI in any case. |
| Policy text | `POLICIES.adminAup` in `ecommerce-frontend/src/legal/policies.ts`, page at `/legal/admin-aup` (the existing `/legal/:slug` route; `compliance-consent.md` proposed `/admin/legal/aup`, and this corrects it). Covers: use customer data only for the task at hand, no export or sharing outside the business, report suspected breaches to the Grievance Officer, no shared credentials, activity is logged and reviewed. **Draft text, needs the owner's or a lawyer's review.** |
| Frontend | `consentAPI` adds `admin_aup` to `ConsentPurpose`; `ConsentPrompt.POLICY_OF` maps it to `adminAup`. Nothing else in the dialog changes. |
| Validation | `ConsentController.DecisionRequest.purpose` pattern adds `admin_aup`. |

Rollout: existing admins are prompted at their next page load after deploy. Bumping `LEGAL_ADMIN_AUP_VERSION` re-prompts all admins.

## 3. Part B — audit trail

### 3.1 New module `audit` (hexagonal like the others)

```
audit/
  AuditTrail.java            public port: record(AuditEvent)            <- other modules call this
  AuditEvent.java            public value type + builder
  Changes.java               public helper: diff(before, after, FieldPolicy)
  application/AuditService   resolves actor + request context, redacts, writes, never throws
  domain/AuditEntry          immutable record; AuditRepository (append + search only, no update/delete)
  infra/mongo/               AuditDocument, MongoAuditRepository
  web/AuditController        GET /api/v1/admin/audit
```

`audit` depends only on `shared` (open module). Other modules depend on `audit`'s root package. `ModularityTests` keeps this honest.

### 3.2 Entry shape (`audit_log`, append-only)

```
{ _id, at, tenantId?: null,                  // tenantId reserved for the multi-tenancy work
  actorId?, actorRole: "ADMIN"|"USER"|"ANONYMOUS"|"SYSTEM",
  action: "PRODUCT_UPDATED",                 // fixed enum, see 3.3
  entityType?: "product", entityId?,
  outcome: "SUCCESS"|"FAILURE",
  changes: [{ field, before?, after?, redacted: boolean }],
  subjectHash?,                              // salted hash of an attempted login email (LOGIN_FAILED only)
  requestId?, ipHash, userAgent }
```

- `actorId`, role and request context are read from the `SecurityContext` and `RequestContextHolder` by `AuditService`; callers do not
  pass them, so a caller cannot forge or forget them. Outside a request (bootstrap, scheduled work) the actor is `SYSTEM`.
- `ipHash` uses the same salted SHA-256 as consent evidence; the hashing moves to `shared.security.IpHasher` so both modules share one
  implementation. The raw IP is never stored.
- `requestId` is the trace id from the logging MDC when present, otherwise empty. The plan verifies what the app actually puts in MDC.
- Indexes: `(entityType, entityId, at desc)`, `(actorId, at desc)`, `(action, at desc)`, and a **TTL index on `at`** from
  `app.audit.retention-days` (default **365**, matching the one-year log rule in `compliance-consent.md` §7). Mirrors how
  `payment_intents` and `password_reset_tokens` get their TTL.

### 3.3 What gets recorded

| Area | Actions | Written from |
|---|---|---|
| Catalogue | `PRODUCT_CREATED/UPDATED/DELETED`, `CATEGORY_CREATED/UPDATED/DELETED` (admin writes only; checkout stock decrements are not audited) | `ProductService`, `CategoryService` |
| Coupons | `COUPON_CREATED/UPDATED/DELETED` (redemptions are not audited; the redemption counters already are the record) | `CouponService` |
| Orders | `ORDER_PLACED`, `ORDER_STATUS_CHANGED` (before/after status), `ORDER_DELETED` | `CheckoutService`, `OrderService` |
| Users | `USER_REGISTERED`, `USER_ROLE_CHANGED`, `USER_STATUS_CHANGED`, `USER_ERASED` (by admin or `ACCOUNT_DELETED_SELF`), `PROFILE_UPDATED`, `ADDRESS_ADDED/UPDATED/DELETED` | `AuthService`, `UserAdminService`, `AccountService`, `ProfileService` |
| Authentication | `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `LOGIN_BLOCKED_SUSPENDED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED` | `AuthService`, `PasswordResetService` |
| Consent | `CONSENT_CHANGED` (purpose, granted, version). The ledger stays the legal proof; this entry only puts it in the one admin view. | `ConsentService` |

Each write path adds one line after the business write succeeds. A failed login records `outcome=FAILURE` with `subjectHash` and no actor.
Flood risk from failed logins is bounded by the existing per-IP limiter (`AuthRateLimitFilter`) and by the TTL.

### 3.4 Redaction and erasure (the part that must not go wrong)

- **Allowlist per entity.** `Changes.diff(before, after, FieldPolicy)` only emits fields the entity's policy names. A new field added
  to `Product` is not logged until someone adds it to the policy, which is the safe default.
- **Never logged:** password hashes, reset tokens, JWTs, payment secrets, raw IPs.
- **Personal fields** (email, name, phone, address lines, billing, recipient, notes, DOB) are recorded as `{field, redacted: true}`:
  *that* it changed, not the values. Business fields (price, stock, category, status, coupon amount, role) are logged with values.
- **Erasure.** Entries hold an opaque `actorId` / `entityId` and no personal values, so deleting an account needs no rewrite of the
  log. `subjectHash` is a salted hash of an attempted email and is pseudonymous; it expires with the TTL and is disclosed in the
  retention table.
- **Append-only.** `AuditRepository` exposes `append` and `search`. There is no update or delete in the port or the adapter, and the
  admin API has no write endpoint. (This protects against application bugs and misuse, not against someone with database access;
  see non-goals.)

### 3.5 Failure behaviour

`AuditService.record` catches everything, logs it, and increments `sixpack.audit.write{result=failure}`. **Auditing never makes a
business action fail.** The trade-off is that a Mongo outage can drop entries; the metric and an alert rule (ADR-012 stack) make
that visible. Writes are synchronous on the request thread, because the volume is low and asynchronous writes would lose the actor.

### 3.6 Reading the log

`GET /api/v1/admin/audit` (🛡) with `entityType, entityId, actorId, action, outcome, from, to, page`; response
`{success, entries, totalPage}`, newest first, same page size style as products. `SecurityConfig` adds
`/api/v1/admin/**` → `hasRole(ADMIN)`. The admin SPA gets an **Audit log** page (filter bar + table + expandable change set) in the
existing admin surface and design tokens. Reading the log is not itself audited.

## 4. Error handling

| Case | Behaviour |
|---|---|
| Admin without current AUP calls an admin endpoint | 403, message above; `/consent/me` lists `admin_aup` in `pending` |
| Non-admin posts `admin_aup` | 400 |
| Audit write fails | Business action unaffected; log + metric |
| Bad audit filter values (unknown action, malformed date) | 400 |
| Audit search with no matches | 200 with an empty list |

## 5. Testing

- **Unit:** `Changes.diff` (added, removed, unchanged, redacted fields, allowlist drops unknown fields); `Purpose.requiredFor`; the
  admin-agreement rule in `ConsentService.state`.
- **Integration (extends `IntegrationTest`):**
  - an admin without `admin_aup` gets 403 on an admin endpoint, accepts via `/consent`, then gets 200; bumping the version re-blocks;
  - a customer never sees `admin_aup` in `pending` and cannot grant it;
  - each audited area produces one entry with the right actor, action and entity, and the entry holds no email, phone, address or
    password text (assert against the stored document, not only the API);
  - `LOGIN_FAILED` stores `subjectHash`, not the email; a successful login stores the user id;
  - erasing a user leaves no personal value in `audit_log`;
  - a non-admin gets 403 on `/admin/audit`; filters work; ordering is newest first;
  - an `AuditRepository` failure does not fail a product update;
  - the repository port exposes no update or delete.
- **Architecture:** `ModularityTests` passes with the new module.
- **Frontend:** the consent dialog shows the AUP for an admin and not for a customer; the audit page renders and filters.

## 6. Documentation to update with the code

`compliance-consent.md` (tick P8/C12, add `admin_aup` to the purposes and §6 model, add `audit_log` and `subjectHash` to the retention
table, fix the AUP route), `contracts.md` (`audit_log`, `GET /admin/audit`, `admin_aup`), `architecture.md` (ADR-019: explicit audit
port, redaction by allowlist, server-enforced admin agreement; rejected: events, persistence callbacks), `requirements.md`,
`test-rollout.md` (new env vars, alert on `sixpack.audit.write{result=failure}`), and `policies.ts` (retention wording for the
audit log).

## 7. Multi-tenancy readiness

Both pieces are written so the tenancy plan can absorb them without rework: `audit_log.tenantId` is reserved, and in the tenancy
draft `consents` becomes T+ with per-tenant versions, so `LEGAL_ADMIN_AUP_VERSION` will move onto the `Tenant` record alongside the
other two. Nothing here depends on tenancy being approved.

## 8. Defaults I picked (change any of these)

1. Retention 365 days for every audit action. It is the stated minimum for logs, and one TTL keeps it simple.
2. Draft AUP wording is short and generic; it ships behind your review.
3. Stock changes are audited only when an admin edits a product; the automatic decrements at checkout stay out of the log.
