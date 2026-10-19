# Admin Agreement and Audit Trail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make admins accept an acceptable-use agreement before they can use any admin endpoint, and add an append-only audit trail of data changes and authentication/security events that admins can search.

**Architecture:** The agreement reuses the consent ledger (`Purpose.ADMIN_AUP`, required for admins only) and the existing `ConsentPrompt` dialog, enforced on the server by a `HandlerInterceptor`. The audit trail is a new `audit` module with one public port, `AuditTrail.record(AuditEvent)`, called by services after each successful write. `AuditService` resolves the actor and request context itself, redacts through a per-entity field allowlist, and never throws.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.13, Spring Data MongoDB, JUnit 5 + AssertJ + MockMvc + Testcontainers `mongo:7.0`; React + TypeScript + Redux Toolkit Query + react-table.

**Spec:** `docs/superpowers/specs/2026-10-18-admin-agreement-and-audit-design.md` (this plan corrects four small points in it; Task 9 updates the spec to match: policy slug `admin-aup`, retention property `app.audit.retention`, a hashing helper called `EvidenceHasher`, and the audit page size of 100).

## Rulings built into this plan

1. **The admin agreement gates every request from an ADMIN token** except `/api/v1/user/me/**`, `/api/v1/consent/**` and `/api/v1/legal/**`. That includes an admin shopping as a customer. The dialog blocks the UI anyway. *Cost if wrong:* an admin who has not accepted cannot check out.
2. **The role comes from the JWT**, because `consent` cannot call `user` (cycle). A user promoted to admin gets the prompt on their next login, when their token carries the new role.
3. **Callers never pass the actor.** `AuditService` reads it from the security context. Only pre-authentication events (`USER_REGISTERED`, `LOGIN_*`, `PASSWORD_RESET_COMPLETED`) pass it explicitly with `AuditEvent.by(...)`.
4. **Retention is a Spring Data TTL index** on `audit_log.at`, from `app.audit.retention` (default `365d`). Changing the value on an existing database needs the old index dropped first (Mongo refuses to change an index's options in place). Task 9 documents this.
5. **No frontend test runner exists** in this repo, so frontend tasks are verified with `npm run build`, `npm run lint` and a manual browser check (Task 9).
6. **Commits:** each task ends with a commit step. Run it only if you are authorised to commit. Every commit message ends with the trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`, passed as a second `-m`.

## Global Constraints

- Retention default: **365 days** for every audit action.
- Audit entries never contain: password hashes, reset tokens, JWTs, payment secrets, raw IPs, or personal values (email, name, phone, address lines, billing, recipient, notes, DOB). Personal fields are recorded as `{field, redacted: true}` only.
- `audit_log` is append-only: `AuditRepository` has `append` and `search` and nothing else; there is no write endpoint.
- Auditing never makes a business action fail: `AuditService.record` catches everything, logs, and increments `sixpack.audit.write{result=failure}`.
- `AuditEntry.tenantId` is reserved and always null for now.
- Module boundary: `audit` depends only on `shared` (open module). Other modules call `com.sixpack.audit.AuditTrail` (root package). `ModularityTests` must stay green.
- Responses use `ApiResponse` (`{success: true, ...}`) and errors use `ApiException`.
- Backend tests: `cd backend && mvn -q -Dtest=<Class> test` (Docker must be running for Testcontainers). Final gate: `mvn verify`, `npm run build`, `npm run lint`.
- Never log or assert on personal data in test output beyond what the test itself created.

## Review Focus

1. **Personal values must never reach `audit_log`**, including failed logins (email), profile edits (name, phone), address changes and account erasure. The tests assert against the stored documents, not the API (Tasks 4, 7).
2. **A failing audit write must not fail the business action** (Task 4).
3. **An admin who has not accepted must still be able to load and answer the dialog**, and must not be locked out of `/user/me`, `/consent` or `/legal` (Task 1). A customer must never be able to grant `admin_aup` (Task 1).
4. **Bad audit filters and paging must return 400 or an empty list, never 500** (unknown action, malformed date, page 0, a page past the end) (Task 5).
5. **Only admins can read the log**; a customer gets 403 and an anonymous caller 401 (Task 5).

## File Structure

```
backend/src/main/java/com/sixpack/
  audit/                                  NEW MODULE
    AuditTrail.java                       public port
    AuditEvent.java                       public event value type (builder-style)
    AuditAction.java                      public enum of every action
    FieldChange.java                      public record: one changed field
    Changes.java                          public helper: allowlist diff
    application/AuditService.java         implements AuditTrail
    domain/AuditEntry.java, AuditQuery.java, AuditRepository.java
    infra/mongo/AuditDocument.java, MongoAuditRepository.java
    web/AuditController.java              GET /api/v1/admin/audit
  consent/                                MODIFIED (Purpose, LegalProperties, ConsentService, ConsentController)
    web/AdminAgreementInterceptor.java    NEW
    web/ConsentWebConfig.java             NEW
  shared/security/EvidenceHasher.java     NEW (salted SHA-256, moved out of ConsentService)
  product/application/CatalogAudit.java   NEW (field policies + snapshot views)
  coupon/application/CouponAudit.java     NEW
  user/application/UserAudit.java         NEW
  (ProductService, CategoryService, CouponService, OrderService, CheckoutService, AuthService,
   UserAdminService, AccountService, ProfileService, PasswordResetService modified)
ecommerce-frontend/src/
  legal/policies.ts, components/consentPrompt.tsx, redux/api/consentAPI.ts   MODIFIED
  redux/api/auditAPI.ts, pages/admin/audit.tsx                               NEW
  redux/store.ts, App.tsx, components/admin/AdminSidebar.tsx                 MODIFIED
```

---

### Task 1: Admin agreement — backend

**Files:**
- Modify: `backend/src/main/java/com/sixpack/consent/Purpose.java`
- Modify: `backend/src/main/java/com/sixpack/consent/application/LegalProperties.java`
- Modify: `backend/src/main/java/com/sixpack/consent/application/ConsentService.java`
- Modify: `backend/src/main/java/com/sixpack/consent/web/ConsentController.java`
- Create: `backend/src/main/java/com/sixpack/consent/web/AdminAgreementInterceptor.java`
- Create: `backend/src/main/java/com/sixpack/consent/web/ConsentWebConfig.java`
- Modify: `backend/src/main/resources/application.yml:44-48`
- Modify: `.env.example:22-23`
- Modify: `backend/src/test/java/com/sixpack/it/IntegrationTest.java`
- Test: `backend/src/test/java/com/sixpack/it/AdminAgreementIntegrationTest.java`

**Interfaces:**
- Consumes: `ConsentLedger.hasAcceptedCurrent(String userId, Purpose purpose)`, `AuthUser.from(Jwt)`.
- Produces: `Purpose.ADMIN_AUP` (wire `admin_aup`), `Purpose.adminOnly()`, `Purpose.requiredFor(boolean admin)`, `ConsentService.state(String userId, boolean admin)`, `ConsentService.decide(String userId, boolean admin, Purpose, boolean granted, Source, ConsentEvidence)`, test helpers `IntegrationTest.adminTokenWithoutAgreement()` and `IntegrationTest.adminToken()` (now accepts the agreement).

- [ ] **Step 1: Split the admin test helper**

In `IntegrationTest.java`, rename the existing `adminToken()` body to `adminTokenWithoutAgreement()` and add a new `adminToken()` that accepts the agreement. Replace the whole existing `adminToken` method with:

```java
    /** Logs the seeded admin in. The admin has NOT accepted the acceptable-use agreement yet. */
    protected String adminTokenWithoutAgreement() throws Exception {
        if (users.findByEmail("admin@test.dev").isEmpty()) {
            users.save(new User(null, "Admin", "admin@test.dev", encoder.encode("admin-password-123"), "",
                    Role.ADMIN, Gender.MALE, LocalDate.of(1990, 1, 1), null));
        }
        MvcResult res = mvc.perform(post("/api/v1/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "admin@test.dev",
                                "password", "admin-password-123"))))
                .andExpect(status().isOk()).andReturn();
        return body(res).get("token").asText();
    }

    /** Logs the seeded admin in and accepts the admin agreement, so admin endpoints work. */
    protected String adminToken() throws Exception {
        String token = adminTokenWithoutAgreement();
        mvc.perform(post("/api/v1/consent").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("purpose", "admin_aup", "granted", true, "source", "reprompt"))))
                .andExpect(status().isOk());
        return token;
    }
```

- [ ] **Step 2: Write the failing tests**

Create `backend/src/test/java/com/sixpack/it/AdminAgreementIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AdminAgreementIntegrationTest extends IntegrationTest {

    @Test
    void anAdminIsAskedForTheAgreementAlongsideTermsAndPrivacy() throws Exception {
        String token = adminTokenWithoutAgreement();
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending", containsInAnyOrder("terms", "privacy", "admin_aup")));
    }

    @Test
    void adminEndpointsAreRefusedUntilTheAgreementIsAccepted() throws Exception {
        String token = adminTokenWithoutAgreement();

        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("acceptable-use")));

        // The dialog's own endpoints must stay reachable, or the admin could never accept.
        mvc.perform(get("/api/v1/user/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/legal/versions")).andExpect(status().isOk());

        decide(token, "admin_aup", true).andExpect(status().isOk());
        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token))).andExpect(status().isOk());
    }

    @Test
    void aNewAgreementVersionBlocksAdminsAgain() throws Exception {
        String token = adminToken();
        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token))).andExpect(status().isOk());

        mongo.getCollection("consents").updateMany(new Document("purpose", "admin_aup"),
                new Document("$set", new Document("policyVersion", "2020-01-01")));

        mvc.perform(get("/api/v1/user/all").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                // The seeded admin never accepted Terms or Privacy either, so they are still pending too.
                .andExpect(jsonPath("$.pending", containsInAnyOrder("terms", "privacy", "admin_aup")));
    }

    @Test
    void customersAreNeverAskedAndCannotAcceptIt() throws Exception {
        String token = register("customer-aup@test.dev");
        mvc.perform(get("/api/v1/consent/me").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.pending", hasSize(0)));
        decide(token, "admin_aup", true).andExpect(status().isBadRequest());
    }

    @Test
    void theAgreementCannotBeWithdrawn() throws Exception {
        String token = adminToken();
        decide(token, "admin_aup", false).andExpect(status().isBadRequest());
    }

    @Test
    void theAgreementVersionIsPublic() throws Exception {
        mvc.perform(get("/api/v1/legal/versions"))
                .andExpect(jsonPath("$.versions.admin_aup").value("2026-10-18"));
    }

    private ResultActions decide(String token, String purpose, boolean granted) throws Exception {
        return mvc.perform(post("/api/v1/consent").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("purpose", purpose, "granted", granted, "source", "reprompt"))));
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=AdminAgreementIntegrationTest test`
Expected: FAIL. `adminToken()` gets 400 on `admin_aup` ("must be terms, privacy or marketing_email"), so most tests fail at the helper.

- [ ] **Step 4: Add the purpose**

Replace `Purpose.java` with:

```java
package com.sixpack.consent;

/**
 * What a user can agree to. Terms and Privacy are required to hold an account; the admin agreement is required
 * only for admins; marketing is optional.
 */
public enum Purpose {
    TERMS(true, false), PRIVACY(true, false), MARKETING_EMAIL(false, false), ADMIN_AUP(true, true);

    private final boolean required;
    private final boolean adminOnly;

    Purpose(boolean required, boolean adminOnly) {
        this.required = required;
        this.adminOnly = adminOnly;
    }

    /** Required to hold the account (for whoever it applies to), so it cannot be withdrawn. */
    public boolean required() {
        return required;
    }

    /** Only an admin can grant it. */
    public boolean adminOnly() {
        return adminOnly;
    }

    /** Whether this account must hold a current grant: admin-only purposes bind admins and nobody else. */
    public boolean requiredFor(boolean admin) {
        return required && (admin || !adminOnly);
    }

    public String wireValue() {
        return name().toLowerCase();
    }

    public static Purpose fromWire(String value) {
        return valueOf(value.trim().toUpperCase());
    }
}
```

- [ ] **Step 5: Add the version property**

Replace `LegalProperties.java` with:

```java
package com.sixpack.consent.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param termsVersion    version (effective date) of the Terms users must have accepted. Bump only for a material
 *                        change: every user is asked to accept again on their next visit.
 * @param privacyVersion  same for the Privacy Policy
 * @param ipHashSalt      salt for hashing the IP stored as consent evidence, so the raw IP is never kept
 * @param adminAupVersion same for the admin acceptable-use agreement: bumping it re-prompts every admin
 */
@ConfigurationProperties("app.legal")
public record LegalProperties(String termsVersion, String privacyVersion, String ipHashSalt,
                              String adminAupVersion) {
}
```

In `application.yml`, add under `app.legal` (after `privacy-version`):

```yaml
    admin-aup-version: ${LEGAL_ADMIN_AUP_VERSION:2026-10-18}
```

In `.env.example`, after `LEGAL_PRIVACY_VERSION=2026-10-16` add:

```
LEGAL_ADMIN_AUP_VERSION=2026-10-18
```

- [ ] **Step 6: Make the consent service role-aware**

In `ConsentService.java` make these changes.

Replace `currentVersion` and `currentVersions`:

```java
    @Override
    public String currentVersion(Purpose purpose) {
        // Marketing consent is described in the Privacy Policy, so it follows that version.
        return switch (purpose) {
            case TERMS -> legal.termsVersion();
            case ADMIN_AUP -> legal.adminAupVersion();
            case PRIVACY, MARKETING_EMAIL -> legal.privacyVersion();
        };
    }

    public Map<Purpose, String> currentVersions() {
        return Map.of(Purpose.TERMS, legal.termsVersion(), Purpose.PRIVACY, legal.privacyVersion(),
                Purpose.ADMIN_AUP, legal.adminAupVersion());
    }
```

Replace `state` and `decide`:

```java
    /** @param admin whether the caller holds the ADMIN role (taken from the JWT; {@code consent} can't call {@code user}) */
    public ConsentState state(String userId, boolean admin) {
        List<ConsentRecord> history = records.findByUser(userId);
        Map<Purpose, ConsentRecord> latest = latest(history);
        List<Purpose> pending = Arrays.stream(Purpose.values())
                .filter(p -> p.requiredFor(admin))
                .filter(p -> !isCurrentGrant(latest.get(p)))
                .toList();
        return new ConsentState(latest, pending, history);
    }

    public ConsentState decide(String userId, boolean admin, Purpose purpose, boolean granted, Source source,
                               ConsentEvidence evidence) {
        if (source == Source.REGISTER) {
            throw ApiException.badRequest("source: must be reprompt or settings");
        }
        if (purpose.adminOnly() && !admin) {
            throw ApiException.badRequest("purpose: only admins can accept this agreement");
        }
        if (purpose.required() && !granted) {
            throw ApiException.badRequest(purpose.adminOnly()
                    ? "The admin agreement can't be withdrawn while you are an admin."
                    : "The Terms and Privacy Policy can't be withdrawn while you have an account. "
                            + "Delete your account instead.");
        }
        append(userId, purpose, granted, source, evidence);
        return state(userId, admin);
    }
```

- [ ] **Step 7: Update the controller**

In `ConsentController.java`:

Change the `purpose` pattern in `DecisionRequest`:

```java
            @NotBlank @Pattern(regexp = "(?i)terms|privacy|marketing_email|admin_aup",
                    message = "must be terms, privacy, marketing_email or admin_aup") String purpose,
```

Replace `mine` and `decide`:

```java
    @GetMapping("/api/v1/consent/me")
    Map<String, Object> mine(@AuthenticationPrincipal Jwt jwt) {
        AuthUser caller = AuthUser.from(jwt);
        return view(consents.state(caller.id(), caller.admin()));
    }

    @PostMapping("/api/v1/consent")
    Map<String, Object> decide(@Valid @RequestBody DecisionRequest request, @AuthenticationPrincipal Jwt jwt,
                               HttpServletRequest http) {
        AuthUser caller = AuthUser.from(jwt);
        return view(consents.decide(caller.id(), caller.admin(), Purpose.fromWire(request.purpose()),
                request.granted(), Source.fromWire(request.source()), evidence(http)));
    }
```

- [ ] **Step 8: Add the interceptor and its registration**

Create `backend/src/main/java/com/sixpack/consent/web/AdminAgreementInterceptor.java`:

```java
package com.sixpack.consent.web;

import com.sixpack.consent.ConsentLedger;
import com.sixpack.consent.Purpose;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses every request from an admin who has not accepted the current acceptable-use agreement
 * (compliance-consent.md C12). Runs after authentication, so the role is the verified one. The dialog's own
 * endpoints are exempt, or the admin could never accept.
 */
@Component
class AdminAgreementInterceptor implements HandlerInterceptor {

    static final String MESSAGE = "Please accept the admin acceptable-use agreement to continue";

    private static final List<String> EXEMPT = List.of("/api/v1/user/me", "/api/v1/consent", "/api/v1/legal");

    private final ConsentLedger consents;

    AdminAgreementInterceptor(ConsentLedger consents) {
        this.consents = consents;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return true;
        }
        AuthUser caller = AuthUser.from(token.getToken());
        if (!caller.admin() || exempt(request.getRequestURI())) {
            return true;
        }
        if (!consents.hasAcceptedCurrent(caller.id(), Purpose.ADMIN_AUP)) {
            throw ApiException.forbidden(MESSAGE);
        }
        return true;
    }

    private static boolean exempt(String uri) {
        return EXEMPT.stream().anyMatch(prefix -> uri.equals(prefix) || uri.startsWith(prefix + "/"));
    }
}
```

Create `backend/src/main/java/com/sixpack/consent/web/ConsentWebConfig.java`:

```java
package com.sixpack.consent.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class ConsentWebConfig implements WebMvcConfigurer {

    private final AdminAgreementInterceptor adminAgreement;

    ConsentWebConfig(AdminAgreementInterceptor adminAgreement) {
        this.adminAgreement = adminAgreement;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAgreement).addPathPatterns("/api/v1/**");
    }
}
```

- [ ] **Step 9: Run the new tests**

Run: `cd backend && mvn -q -Dtest=AdminAgreementIntegrationTest test`
Expected: PASS (6 tests).

- [ ] **Step 10: Run the whole backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS. Every other test reaches admin endpoints through `adminToken()`, which now accepts the agreement. If a test fails with 403 "acceptable-use", it builds an admin token some other way; make it call `adminToken()`.

- [ ] **Step 11: Commit**

```bash
git add backend .env.example
git commit -m "feat(consent): admins must accept the acceptable-use agreement, enforced on the server" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Admin agreement — frontend

**Files:**
- Modify: `ecommerce-frontend/src/legal/policies.ts` (`PolicySlug`, `POLICIES`)
- Modify: `ecommerce-frontend/src/redux/api/consentAPI.ts:4`
- Modify: `ecommerce-frontend/src/components/consentPrompt.tsx:9-13`

**Interfaces:**
- Consumes: backend `admin_aup` purpose (Task 1).
- Produces: policy slug `"admin-aup"`, page `/legal/admin-aup`, `ConsentPurpose` including `"admin_aup"`.

- [ ] **Step 1: Add the policy text**

In `policies.ts`, change the slug type:

```ts
export type PolicySlug =
  | "terms"
  | "privacy"
  | "refunds"
  | "shipping"
  | "contact"
  | "cookies"
  | "admin-aup";
```

Add this entry to `POLICIES` after the `cookies` entry (before the closing `};`):

```ts
  "admin-aup": {
    slug: "admin-aup",
    title: "Admin Acceptable Use Agreement",
    updated: "2026-10-18",
    intro: `This agreement applies to everyone with an admin account on 6Pack, run by ${b.legalName}. Admins can see customers' personal data, so please read it before you continue.`,
    sections: [
      {
        heading: "Using customer data",
        body: [
          "Use customer data only for the task in front of you, such as fulfilling an order, answering a support request or fixing a problem.",
          "Don't copy, export, photograph or share customer data outside 6Pack, and don't look up people you have no work reason to look up.",
          "Treat order contact details, addresses and phone numbers as confidential, even after you stop being an admin.",
        ],
      },
      {
        heading: "Keeping your account safe",
        body: [
          "Use your own account and a strong, unique password. Never share your login or let someone else use it.",
          "Sign out on shared or public devices.",
        ],
      },
      {
        heading: "Reporting problems",
        body: [
          `If you think customer data has been exposed, or your account has been misused, tell the Grievance Officer straight away: ${b.grievanceOfficer}, ${b.grievanceEmail}.`,
        ],
      },
      {
        heading: "Activity is recorded",
        body: [
          "Changes you make to products, categories, coupons, orders and users, and sign-in attempts, are recorded in an audit log with your account, the time and what changed.",
          "The log is kept for one year and is reviewed by other admins. It doesn't record personal details such as customers' names, emails or addresses.",
        ],
      },
      {
        heading: "Accepting this agreement",
        body: [
          "You must accept this agreement to use the admin area. You'll be asked again whenever it changes in a material way.",
          "Misuse of customer data can lead to removal of admin access and, where the law requires, to legal action.",
        ],
      },
    ],
  },
```

Do **not** add `"admin-aup"` to `POLICY_LINKS` (the customer footer must not list it). Update the header comment of the file: change the sentence ending "…also bump LEGAL_TERMS_VERSION / LEGAL_PRIVACY_VERSION on the backend" to "…also bump LEGAL_TERMS_VERSION / LEGAL_PRIVACY_VERSION (or LEGAL_ADMIN_AUP_VERSION for the admin agreement) on the backend".

- [ ] **Step 2: Add the purpose to the API types**

In `consentAPI.ts` change line 4 to:

```ts
export type ConsentPurpose = "terms" | "privacy" | "marketing_email" | "admin_aup";
```

- [ ] **Step 3: Map the purpose to its page in the dialog**

In `consentPrompt.tsx` replace `POLICY_OF` with:

```ts
const POLICY_OF: Record<ConsentPurpose, keyof typeof POLICIES | null> = {
  terms: "terms",
  privacy: "privacy",
  marketing_email: null,
  admin_aup: "admin-aup",
};
```

- [ ] **Step 4: Verify the build and lint**

Run: `cd ecommerce-frontend && npm run build && npm run lint`
Expected: both exit 0 (the `Record<ConsentPurpose, …>` type forces the new key, so a missing mapping fails `tsc`).

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend
git commit -m "feat(consent): admin agreement text and dialog mapping" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Audit public API and the field-diff helper

**Files:**
- Create: `backend/src/main/java/com/sixpack/audit/AuditAction.java`
- Create: `backend/src/main/java/com/sixpack/audit/FieldChange.java`
- Create: `backend/src/main/java/com/sixpack/audit/Changes.java`
- Create: `backend/src/main/java/com/sixpack/audit/AuditEvent.java`
- Create: `backend/src/main/java/com/sixpack/audit/AuditTrail.java`
- Test: `backend/src/test/java/com/sixpack/audit/ChangesTest.java`

**Interfaces:**
- Produces (used by Tasks 4 to 7):
  - `enum AuditAction` with `static AuditAction parse(String)` (throws `ApiException.badRequest`)
  - `record FieldChange(String field, Object before, Object after, boolean redacted)` with `static FieldChange of(String, Object, Object)` and `static FieldChange redacted(String)`
  - `Changes.policy(Collection<String> values, Collection<String> personal)` returning `Changes.FieldPolicy`; `Changes.diff(Map<String, ?> before, Map<String, ?> after, FieldPolicy)` returning `List<FieldChange>`
  - `record AuditEvent(...)` with `of(AuditAction)`, `on(String type, String id)`, `changing(List<FieldChange>)`, `failed()`, `aboutSubject(String)`, `by(String actorId, String actorRole)`
  - `interface AuditTrail { void record(AuditEvent event); }`

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/sixpack/audit/ChangesTest.java`:

```java
package com.sixpack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.shared.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangesTest {

    private static final FieldPolicy POLICY = Changes.policy(List.of("price", "stock", "tags", "startsAt"),
            List.of("email"));

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void reportsOnlyTheFieldsThatChanged() {
        var before = map("price", 10.0, "stock", 3);
        var after = map("price", 12.0, "stock", 3);

        assertThat(Changes.diff(before, after, POLICY))
                .containsExactly(FieldChange.of("price", 10.0, 12.0));
    }

    @Test
    void nullToNullIsNotAChange() {
        assertThat(Changes.diff(map("price", null), map("price", null), POLICY)).isEmpty();
    }

    @Test
    void ignoresFieldsOutsideThePolicyEvenWhenTheyChange() {
        var before = map("price", 10.0, "passwordHash", "old");
        var after = map("price", 10.0, "passwordHash", "new");

        assertThat(Changes.diff(before, after, POLICY)).isEmpty();
    }

    @Test
    void personalFieldsAreFlaggedWithoutTheirValues() {
        var changes = Changes.diff(map("email", "a@x.dev"), map("email", "b@x.dev"), POLICY);

        assertThat(changes).containsExactly(FieldChange.redacted("email"));
        assertThat(changes.get(0).before()).isNull();
        assertThat(changes.get(0).after()).isNull();
        assertThat(changes.get(0).redacted()).isTrue();
    }

    @Test
    void anUnchangedPersonalFieldIsNotListed() {
        assertThat(Changes.diff(map("email", "a@x.dev"), map("email", "a@x.dev"), POLICY)).isEmpty();
    }

    @Test
    void aCreationListsEveryFieldThatHasAValue() {
        var changes = Changes.diff(Map.of(), map("price", 5.0, "stock", 2, "email", "a@x.dev"), POLICY);

        assertThat(changes).containsExactlyInAnyOrder(FieldChange.of("price", null, 5.0),
                FieldChange.of("stock", null, 2), FieldChange.redacted("email"));
    }

    @Test
    void aDeletionListsEveryFieldThatHadAValue() {
        var changes = Changes.diff(map("price", 5.0), Map.of(), POLICY);

        assertThat(changes).containsExactly(FieldChange.of("price", 5.0, null));
    }

    @Test
    void collectionsAndOtherTypesAreStoredAsPlainValues() {
        var when = Instant.parse("2026-10-18T10:00:00Z");
        var changes = Changes.diff(map("tags", List.of("a")), map("tags", List.of("a", "b")), POLICY);
        assertThat(changes).containsExactly(FieldChange.of("tags", List.of("a"), List.of("a", "b")));

        assertThat(Changes.diff(Map.of(), map("startsAt", when), POLICY))
                .containsExactly(FieldChange.of("startsAt", null, "2026-10-18T10:00:00Z"));
    }

    @Test
    void anUnknownActionIsABadRequest() {
        assertThat(AuditAction.parse(" product_created ")).isEqualTo(AuditAction.PRODUCT_CREATED);
        assertThatThrownBy(() -> AuditAction.parse("NOPE")).isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=ChangesTest test`
Expected: FAIL (compilation errors: `Changes`, `FieldChange`, `AuditAction` do not exist).

- [ ] **Step 3: Add the action enum**

Create `backend/src/main/java/com/sixpack/audit/AuditAction.java`:

```java
package com.sixpack.audit;

import com.sixpack.shared.ApiException;

/** Every kind of event the audit trail records. The name is what is stored and what the admin API filters on. */
public enum AuditAction {
    PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED,
    CATEGORY_CREATED, CATEGORY_UPDATED, CATEGORY_DELETED,
    COUPON_CREATED, COUPON_UPDATED, COUPON_DELETED,
    ORDER_PLACED, ORDER_STATUS_CHANGED, ORDER_DELETED,
    USER_REGISTERED, USER_ROLE_CHANGED, USER_STATUS_CHANGED, USER_ERASED, ACCOUNT_DELETED_SELF,
    PROFILE_UPDATED, ADDRESS_ADDED, ADDRESS_UPDATED, ADDRESS_DELETED,
    LOGIN_SUCCEEDED, LOGIN_FAILED, LOGIN_BLOCKED_SUSPENDED,
    PASSWORD_RESET_REQUESTED, PASSWORD_RESET_COMPLETED,
    CONSENT_CHANGED;

    public static AuditAction parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("action: unknown action");
        }
    }
}
```

- [ ] **Step 4: Add the change record and the diff helper**

Create `backend/src/main/java/com/sixpack/audit/FieldChange.java`:

```java
package com.sixpack.audit;

/**
 * One field of an entity that changed. A personal field has {@code redacted = true} and no values: the log says
 * that it changed, never what it was.
 */
public record FieldChange(String field, Object before, Object after, boolean redacted) {

    public static FieldChange of(String field, Object before, Object after) {
        return new FieldChange(field, before, after, false);
    }

    public static FieldChange redacted(String field) {
        return new FieldChange(field, null, null, true);
    }
}
```

Create `backend/src/main/java/com/sixpack/audit/Changes.java`:

```java
package com.sixpack.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the change list for an audit entry. Only fields named by the entity's {@link FieldPolicy} are ever
 * emitted, so a field added to an entity later is not logged until someone decides it should be.
 */
public final class Changes {

    /**
     * @param values   fields logged with their before/after values (prices, stock, status, role…)
     * @param personal fields logged as "changed" only, with no values (email, name, phone…)
     */
    public record FieldPolicy(Set<String> values, Set<String> personal) {
    }

    private Changes() {
    }

    public static FieldPolicy policy(Collection<String> values, Collection<String> personal) {
        return new FieldPolicy(new LinkedHashSet<>(values), new LinkedHashSet<>(personal));
    }

    /**
     * @param before the snapshot before the change; {@code Map.of()} for a creation
     * @param after  the snapshot after the change; {@code Map.of()} for a deletion
     */
    public static List<FieldChange> diff(Map<String, ?> before, Map<String, ?> after, FieldPolicy policy) {
        List<FieldChange> changes = new ArrayList<>();
        for (String field : policy.values()) {
            Object b = plain(before.get(field));
            Object a = plain(after.get(field));
            if (!Objects.equals(b, a)) {
                changes.add(FieldChange.of(field, b, a));
            }
        }
        for (String field : policy.personal()) {
            if (!Objects.equals(before.get(field), after.get(field))) {
                changes.add(FieldChange.redacted(field));
            }
        }
        return List.copyOf(changes);
    }

    /** Strings, numbers and booleans pass through; collections are normalised; anything else becomes its text. */
    private static Object plain(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> items) {
            return items.stream().map(Changes::plain).toList();
        }
        return value.toString();
    }
}
```

- [ ] **Step 5: Add the event type and the port**

Create `backend/src/main/java/com/sixpack/audit/AuditEvent.java`:

```java
package com.sixpack.audit;

import java.util.List;

/**
 * What a service tells the audit trail. The actor, time, IP and request id are not here on purpose:
 * {@code AuditService} reads them from the request, so a caller can neither forge nor forget them. Only events
 * that happen before a token exists (register, login, password reset) name the actor with {@link #by}.
 *
 * @param subject a raw identifier such as an attempted login email; it is hashed before it is stored
 */
public record AuditEvent(AuditAction action, String entityType, String entityId, Outcome outcome,
                         List<FieldChange> changes, String subject, String actorId, String actorRole) {

    public enum Outcome { SUCCESS, FAILURE }

    public AuditEvent {
        outcome = outcome == null ? Outcome.SUCCESS : outcome;
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    public static AuditEvent of(AuditAction action) {
        return new AuditEvent(action, null, null, Outcome.SUCCESS, List.of(), null, null, null);
    }

    public AuditEvent on(String entityType, String entityId) {
        return new AuditEvent(action, entityType, entityId, outcome, changes, subject, actorId, actorRole);
    }

    public AuditEvent changing(List<FieldChange> newChanges) {
        return new AuditEvent(action, entityType, entityId, outcome, newChanges, subject, actorId, actorRole);
    }

    public AuditEvent failed() {
        return new AuditEvent(action, entityType, entityId, Outcome.FAILURE, changes, subject, actorId, actorRole);
    }

    public AuditEvent aboutSubject(String newSubject) {
        return new AuditEvent(action, entityType, entityId, outcome, changes, newSubject, actorId, actorRole);
    }

    public AuditEvent by(String newActorId, String newActorRole) {
        return new AuditEvent(action, entityType, entityId, outcome, changes, subject, newActorId, newActorRole);
    }
}
```

Create `backend/src/main/java/com/sixpack/audit/AuditTrail.java`:

```java
package com.sixpack.audit;

/** Public API of the audit module. Recording never throws: auditing must not fail the action it describes. */
public interface AuditTrail {

    void record(AuditEvent event);
}
```

- [ ] **Step 6: Run the tests**

Run: `cd backend && mvn -q -Dtest=ChangesTest test`
Expected: PASS (9 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/sixpack/audit backend/src/test/java/com/sixpack/audit
git commit -m "feat(audit): audit port, event type and allowlist diff helper" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Audit persistence and the recording service

**Files:**
- Create: `backend/src/main/java/com/sixpack/shared/security/EvidenceHasher.java`
- Modify: `backend/src/main/java/com/sixpack/consent/application/ConsentService.java` (use the hasher)
- Create: `backend/src/main/java/com/sixpack/audit/domain/AuditEntry.java`, `AuditQuery.java`, `AuditRepository.java`
- Create: `backend/src/main/java/com/sixpack/audit/infra/mongo/AuditDocument.java`, `MongoAuditRepository.java`
- Create: `backend/src/main/java/com/sixpack/audit/application/AuditService.java`
- Modify: `backend/src/main/resources/application.yml` (add `app.audit.retention`)
- Modify: `.env.example`
- Modify: `backend/src/test/java/com/sixpack/it/IntegrationTest.java` (clean `audit_log`, add `auditEntries`, `changeOf`, `placeOrder`)
- Test: `backend/src/test/java/com/sixpack/shared/security/EvidenceHasherTest.java`, `backend/src/test/java/com/sixpack/it/AuditTrailIntegrationTest.java`

**Interfaces:**
- Consumes: `AuditTrail`, `AuditEvent`, `AuditAction`, `FieldChange` (Task 3); `AuthUser.from(Jwt)`.
- Produces: `EvidenceHasher.hash(String)`; `AuditRepository { AuditEntry append(AuditEntry); PageResult<AuditEntry> search(AuditQuery); }`; `record AuditQuery(String entityType, String entityId, String actorId, AuditAction action, AuditEvent.Outcome outcome, Instant from, Instant to, int page, int size)`; Mongo collection `audit_log`; test helpers `auditEntries(String action)` (newest first), `changeOf(Document entry, String field)`, `placeOrder(String token, String productId)` returning the order id.

- [ ] **Step 1: Write the hasher test**

Create `backend/src/test/java/com/sixpack/shared/security/EvidenceHasherTest.java`:

```java
package com.sixpack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvidenceHasherTest {

    @Test
    void hashesWithTheSaltAndNeverReturnsTheInput() {
        var hasher = new EvidenceHasher("pepper");
        String hash = hasher.hash("203.0.113.9");

        assertThat(hash).hasSize(64).doesNotContain("203.0.113.9");
        assertThat(hasher.hash("203.0.113.9")).isEqualTo(hash);
        assertThat(new EvidenceHasher("other").hash("203.0.113.9")).isNotEqualTo(hash);
    }

    @Test
    void aBlankValueHashesToTheEmptyString() {
        var hasher = new EvidenceHasher("pepper");
        assertThat(hasher.hash(null)).isEmpty();
        assertThat(hasher.hash("  ")).isEmpty();
    }
}
```

- [ ] **Step 2: Add the hasher and move `ConsentService` onto it**

Create `backend/src/main/java/com/sixpack/shared/security/EvidenceHasher.java`:

```java
package com.sixpack.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Salted SHA-256 for values we must be able to compare but never store in the clear: the IP kept as consent
 * evidence and in the audit log, and an attempted login email.
 */
@Component
public class EvidenceHasher {

    private final String salt;

    EvidenceHasher(@Value("${app.legal.ip-hash-salt:}") String salt) {
        this.salt = salt == null ? "" : salt;
    }

    /** @return 64 hex characters, or the empty string for a null or blank input */
    public String hash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((salt + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

In `ConsentService.java`: delete the private `hashIp` method and the imports `java.nio.charset.StandardCharsets`, `java.security.MessageDigest`, `java.security.NoSuchAlgorithmException`, `java.util.HexFormat`; add `import com.sixpack.shared.security.EvidenceHasher;`; add the field and constructor parameter, and use it in `append`:

```java
    private final ConsentRepository records;
    private final LegalProperties legal;
    private final EvidenceHasher hasher;

    ConsentService(ConsentRepository records, LegalProperties legal, EvidenceHasher hasher) {
        this.records = records;
        this.legal = legal;
        this.hasher = hasher;
    }
```

and in `append`, replace `hashIp(evidence.ip())` with `hasher.hash(evidence.ip())`.

- [ ] **Step 3: Run the hasher and consent tests**

Run: `cd backend && mvn -q -Dtest='EvidenceHasherTest,ConsentIntegrationTest,AdminAgreementIntegrationTest' test`
Expected: PASS (the consent tests assert a 64-character `ipHash`, so they prove the move kept behaviour).

- [ ] **Step 4: Extend the test base class**

In `IntegrationTest.java`:

Add `"audit_log"` to the collection list in `cleanDatabase`:

```java
        for (String c : new String[] {"users", "products", "orders", "coupons", "payment_intents",
                "password_reset_tokens", "consents", "categories", "coupon_redemptions", "audit_log"}) {
```

Add imports `java.util.ArrayList`, `java.util.List`, `org.bson.Document`, and these helpers (next to `body`):

```java
    /** Audit entries with this action, newest first, read straight from the collection. */
    protected List<Document> auditEntries(String action) {
        return mongo.getCollection("audit_log").find(new Document("action", action))
                .sort(new Document("at", -1).append("_id", -1)).into(new ArrayList<>());
    }

    /** The change recorded for one field of an audit entry. */
    protected Document changeOf(Document entry, String field) {
        return entry.getList("changes", Document.class).stream()
                .filter(c -> field.equals(c.getString("field"))).findFirst().orElseThrow();
    }

    /** Checks out one unit of the product as this customer and confirms the fake payment. Returns the order id. */
    protected String placeOrder(String token, String productId) throws Exception {
        String providerOrderId = body(mvc.perform(post("/api/v1/order/checkout")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items",
                                List.of(Map.of("productId", productId, "quantity", 1))))))
                .andExpect(status().isCreated()).andReturn()).get("checkout").get("providerOrderId").asText();
        return body(mvc.perform(post("/api/v1/order/new").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("providerOrderId", providerOrderId,
                                "paymentId", "fake_pay_1", "signature", "fake",
                                "shippingInfo", Map.of("address", "12 MG Road", "city", "Bengaluru",
                                        "state", "Karnataka", "country", "India", "pinCode", "560001")))))
                .andExpect(status().isCreated()).andReturn()).get("orderId").asText();
    }
```

- [ ] **Step 5: Write the failing audit-service tests**

Create `backend/src/test/java/com/sixpack/it/AuditTrailIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import com.sixpack.audit.domain.AuditRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditTrailIntegrationTest extends IntegrationTest {

    @Autowired
    AuditTrail audit;
    @Autowired
    MeterRegistry meters;
    @MockitoSpyBean
    AuditRepository repository;

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static AuditEvent sample() {
        return AuditEvent.of(AuditAction.PRODUCT_CREATED).on("product", "p1")
                .changing(List.of(FieldChange.of("price", null, 10.0)));
    }

    private Document only() {
        List<Document> all = mongo.getCollection("audit_log").find().into(new java.util.ArrayList<>());
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    void anEventOutsideARequestIsRecordedAsTheSystem() {
        audit.record(sample());

        Document entry = only();
        assertThat(entry.getString("actorRole")).isEqualTo("SYSTEM");
        assertThat(entry.getString("actorId")).isNull();
        assertThat(entry.getString("action")).isEqualTo("PRODUCT_CREATED");
        assertThat(entry.getString("entityType")).isEqualTo("product");
        assertThat(entry.getString("entityId")).isEqualTo("p1");
        assertThat(entry.getString("outcome")).isEqualTo("SUCCESS");
        assertThat(entry.getString("ipHash")).isEmpty();
        assertThat(changeOf(entry, "price").get("after")).isEqualTo(10.0);
        assertThat(entry.get("at")).isNotNull();
    }

    @Test
    void theActorAndRequestContextComeFromTheRequestNotTheCaller() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("User-Agent", "JUnit-Audit");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("admin-1").claim("role", "ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        audit.record(sample());

        Document entry = only();
        assertThat(entry.getString("actorId")).isEqualTo("admin-1");
        assertThat(entry.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(entry.getString("ipHash")).hasSize(64).doesNotContain("203.0.113.9");
        assertThat(entry.getString("userAgent")).isEqualTo("JUnit-Audit");
    }

    @Test
    void anUnauthenticatedRequestIsAnonymousAndAnAttemptedEmailIsOnlyStoredHashed() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED).failed().aboutSubject("Victim@Example.com "));
        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED).failed().aboutSubject("victim@example.com"));

        List<Document> entries = mongo.getCollection("audit_log").find().into(new java.util.ArrayList<>());
        assertThat(entries).hasSize(2).allSatisfy(e -> {
            assertThat(e.getString("actorRole")).isEqualTo("ANONYMOUS");
            assertThat(e.getString("outcome")).isEqualTo("FAILURE");
            assertThat(e.getString("subjectHash")).hasSize(64);
            assertThat(e.toJson().toLowerCase()).doesNotContain("victim");
        });
        assertThat(entries.get(0).getString("subjectHash")).isEqualTo(entries.get(1).getString("subjectHash"));
    }

    @Test
    void anExplicitActorOverridesTheSecurityContext() {
        audit.record(AuditEvent.of(AuditAction.USER_REGISTERED).on("user", "u9").by("u9", "USER"));

        assertThat(only().getString("actorId")).isEqualTo("u9");
        assertThat(only().getString("actorRole")).isEqualTo("USER");
    }

    @Test
    void aFailingWriteNeverReachesTheCallerAndIsCounted() {
        double before = meters.counter("sixpack.audit.write", "result", "failure").count();
        doThrow(new RuntimeException("db down")).when(repository).append(any());

        assertThatCode(() -> audit.record(sample())).doesNotThrowAnyException();

        assertThat(meters.counter("sixpack.audit.write", "result", "failure").count()).isEqualTo(before + 1);
    }

    @Test
    void entriesExpireAfterTheRetentionPeriod() {
        audit.record(sample());

        IndexInfo ttl = mongo.indexOps("audit_log").getIndexInfo().stream()
                .filter(i -> i.getIndexFields().stream().anyMatch(f -> "at".equals(f.getKey())))
                .filter(i -> i.getExpireAfter().isPresent()).findFirst().orElseThrow();
        assertThat(ttl.getExpireAfter()).contains(Duration.ofDays(365));
    }

    @Test
    void theRepositoryCanOnlyAppendAndSearch() {
        assertThat(Arrays.stream(AuditRepository.class.getDeclaredMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("append", "search");
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd backend && mvn -q -Dtest=AuditTrailIntegrationTest test`
Expected: FAIL (compilation: `AuditRepository` does not exist).

- [ ] **Step 7: Add the domain types**

Create `backend/src/main/java/com/sixpack/audit/domain/AuditEntry.java`:

```java
package com.sixpack.audit.domain;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import com.sixpack.audit.FieldChange;
import java.time.Instant;
import java.util.List;

/**
 * One immutable audit record.
 *
 * @param tenantId    reserved for multi-tenancy; always null today
 * @param actorRole   ADMIN, USER, ANONYMOUS (an unauthenticated request) or SYSTEM (no request at all)
 * @param subjectHash salted hash of an attempted login email; never the email itself
 */
public record AuditEntry(String id, Instant at, String tenantId, String actorId, String actorRole,
                         AuditAction action, String entityType, String entityId, Outcome outcome,
                         List<FieldChange> changes, String subjectHash, String requestId, String ipHash,
                         String userAgent) {
}
```

Create `backend/src/main/java/com/sixpack/audit/domain/AuditQuery.java`:

```java
package com.sixpack.audit.domain;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import java.time.Instant;

/** Filters for the admin audit search. Null means "don't filter on this". */
public record AuditQuery(String entityType, String entityId, String actorId, AuditAction action, Outcome outcome,
                         Instant from, Instant to, int page, int size) {
}
```

Create `backend/src/main/java/com/sixpack/audit/domain/AuditRepository.java`:

```java
package com.sixpack.audit.domain;

import com.sixpack.shared.PageResult;

/** Persistence port for the audit log. There is deliberately no update or delete. */
public interface AuditRepository {

    AuditEntry append(AuditEntry entry);

    /** @return the matching entries, newest first, one page at a time (1-based) */
    PageResult<AuditEntry> search(AuditQuery query);
}
```

- [ ] **Step 8: Add the Mongo adapter**

Create `backend/src/main/java/com/sixpack/audit/infra/mongo/AuditDocument.java`:

```java
package com.sixpack.audit.infra.mongo;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Append-only audit log; see docs/superpowers/specs/2026-10-18-admin-agreement-and-audit-design.md. */
@Document("audit_log")
@CompoundIndexes({
        @CompoundIndex(name = "entity_at", def = "{'entityType': 1, 'entityId': 1, 'at': -1}"),
        @CompoundIndex(name = "actor_at", def = "{'actorId': 1, 'at': -1}"),
        @CompoundIndex(name = "action_at", def = "{'action': 1, 'at': -1}")
})
class AuditDocument {

    static class ChangeDocument {
        String field;
        Object before;
        Object after;
        boolean redacted;
    }

    @Id
    String id;
    /** TTL index: entries are deleted this long after they were written. */
    @Indexed(expireAfter = "${app.audit.retention:365d}")
    Instant at;
    String tenantId;
    String actorId;
    String actorRole;
    String action;
    String entityType;
    String entityId;
    String outcome;
    List<ChangeDocument> changes;
    String subjectHash;
    String requestId;
    String ipHash;
    String userAgent;
}
```

Create `backend/src/main/java/com/sixpack/audit/infra/mongo/MongoAuditRepository.java`:

```java
package com.sixpack.audit.infra.mongo;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import com.sixpack.audit.FieldChange;
import com.sixpack.audit.domain.AuditEntry;
import com.sixpack.audit.domain.AuditQuery;
import com.sixpack.audit.domain.AuditRepository;
import com.sixpack.shared.PageResult;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
class MongoAuditRepository implements AuditRepository {

    // _id breaks ties between entries written in the same millisecond (ObjectIds increase).
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "at", "_id");

    private final MongoTemplate mongo;

    MongoAuditRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public AuditEntry append(AuditEntry entry) {
        return toDomain(mongo.insert(toDocument(entry)));
    }

    @Override
    public PageResult<AuditEntry> search(AuditQuery q) {
        Query query = new Query();
        if (q.entityType() != null) {
            query.addCriteria(Criteria.where("entityType").is(q.entityType()));
        }
        if (q.entityId() != null) {
            query.addCriteria(Criteria.where("entityId").is(q.entityId()));
        }
        if (q.actorId() != null) {
            query.addCriteria(Criteria.where("actorId").is(q.actorId()));
        }
        if (q.action() != null) {
            query.addCriteria(Criteria.where("action").is(q.action().name()));
        }
        if (q.outcome() != null) {
            query.addCriteria(Criteria.where("outcome").is(q.outcome().name()));
        }
        if (q.from() != null || q.to() != null) {
            Criteria range = Criteria.where("at");
            if (q.from() != null) {
                range = range.gte(q.from());
            }
            if (q.to() != null) {
                range = range.lte(q.to());
            }
            query.addCriteria(range);
        }
        long total = mongo.count(query, AuditDocument.class);
        query.with(NEWEST_FIRST).skip((long) (q.page() - 1) * q.size()).limit(q.size());
        List<AuditEntry> items = mongo.find(query, AuditDocument.class).stream()
                .map(MongoAuditRepository::toDomain).toList();
        return new PageResult<>(items, total);
    }

    private static AuditDocument toDocument(AuditEntry e) {
        AuditDocument d = new AuditDocument();
        d.at = e.at();
        d.tenantId = e.tenantId();
        d.actorId = e.actorId();
        d.actorRole = e.actorRole();
        d.action = e.action().name();
        d.entityType = e.entityType();
        d.entityId = e.entityId();
        d.outcome = e.outcome().name();
        d.changes = e.changes().stream().map(c -> {
            AuditDocument.ChangeDocument cd = new AuditDocument.ChangeDocument();
            cd.field = c.field();
            cd.before = c.before();
            cd.after = c.after();
            cd.redacted = c.redacted();
            return cd;
        }).toList();
        d.subjectHash = e.subjectHash();
        d.requestId = e.requestId();
        d.ipHash = e.ipHash();
        d.userAgent = e.userAgent();
        return d;
    }

    private static AuditEntry toDomain(AuditDocument d) {
        List<FieldChange> changes = d.changes == null ? List.of()
                : d.changes.stream().map(c -> new FieldChange(c.field, c.before, c.after, c.redacted)).toList();
        return new AuditEntry(d.id, d.at, d.tenantId, d.actorId, d.actorRole, AuditAction.valueOf(d.action),
                d.entityType, d.entityId, Outcome.valueOf(d.outcome), changes, d.subjectHash, d.requestId,
                d.ipHash, d.userAgent);
    }
}
```

- [ ] **Step 9: Add the service**

Create `backend/src/main/java/com/sixpack/audit/application/AuditService.java`:

```java
package com.sixpack.audit.application;

import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.domain.AuditEntry;
import com.sixpack.audit.domain.AuditRepository;
import com.sixpack.shared.security.AuthUser;
import com.sixpack.shared.security.EvidenceHasher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
class AuditService implements AuditTrail {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_USER_AGENT = 256;

    private record Actor(String id, String role) {
    }

    private final AuditRepository entries;
    private final EvidenceHasher hasher;
    private final MeterRegistry meters;

    AuditService(AuditRepository entries, EvidenceHasher hasher, MeterRegistry meters) {
        this.entries = entries;
        this.hasher = hasher;
        this.meters = meters;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            entries.append(toEntry(event));
            meters.counter("sixpack.audit.write", "result", "success").increment();
        } catch (RuntimeException e) {
            // Auditing must never fail the action it describes. The metric (and its alert) is what makes a gap visible.
            log.error("Audit write failed for action={} entity={}/{}", event.action(), event.entityType(),
                    event.entityId(), e);
            meters.counter("sixpack.audit.write", "result", "failure").increment();
        }
    }

    private AuditEntry toEntry(AuditEvent e) {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest() : null;
        Actor actor = actor(e, request != null);
        String userAgent = request == null || request.getHeader(HttpHeaders.USER_AGENT) == null ? ""
                : truncate(request.getHeader(HttpHeaders.USER_AGENT));
        return new AuditEntry(null, Instant.now(), null, actor.id(), actor.role(), e.action(), e.entityType(),
                e.entityId(), e.outcome(), e.changes(), hasher.hash(normalise(e.subject())), traceId(),
                request == null ? "" : hasher.hash(request.getRemoteAddr()), userAgent);
    }

    private static Actor actor(AuditEvent e, boolean inRequest) {
        if (e.actorId() != null) {
            return new Actor(e.actorId(), e.actorRole());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            AuthUser user = AuthUser.from(jwt);
            return new Actor(user.id(), user.admin() ? "ADMIN" : "USER");
        }
        return new Actor(null, inRequest ? "ANONYMOUS" : "SYSTEM");
    }

    private static String normalise(String subject) {
        return subject == null ? null : subject.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value) {
        return value.substring(0, Math.min(value.length(), MAX_USER_AGENT));
    }

    private static String traceId() {
        String id = MDC.get("traceId");
        return id == null ? MDC.get("trace_id") : id;
    }
}
```

- [ ] **Step 10: Add the retention property**

In `application.yml`, add a new block under `app:` (next to `legal:`):

```yaml
  audit:
    # How long audit entries are kept (a Mongo TTL index). Changing it on an existing database needs the old
    # `at_1` index dropped first; see docs/foundations/test-rollout.md.
    retention: ${AUDIT_RETENTION:365d}
```

In `.env.example` add:

```
AUDIT_RETENTION=365d
```

- [ ] **Step 11: Run the tests**

Run: `cd backend && mvn -q -Dtest='AuditTrailIntegrationTest,ModularityTests' test`
Expected: PASS. If `entriesExpireAfterTheRetentionPeriod` fails because the index has no expiry (the `${…}` placeholder was not resolved), replace the annotation with the literal `@Indexed(expireAfter = "365d")`, keep the comment, drop the `app.audit.retention` entry from `application.yml` and `.env.example`, and note in `docs/foundations/test-rollout.md` (Task 9) that retention is a constant. The test then passes unchanged.

- [ ] **Step 12: Commit**

```bash
git add backend .env.example
git commit -m "feat(audit): append-only audit log, recording service and TTL retention" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Admin read API

**Files:**
- Create: `backend/src/main/java/com/sixpack/audit/web/AuditController.java`
- Modify: `backend/src/main/java/com/sixpack/shared/security/SecurityConfig.java` (route rule)
- Test: `backend/src/test/java/com/sixpack/it/AuditApiIntegrationTest.java`

**Interfaces:**
- Consumes: `AuditRepository.search`, `AuditQuery`, `AuditAction.parse`, `AuditTrail`.
- Produces: `GET /api/v1/admin/audit` returning `{success, entries: [{_id, at, actorId, actorRole, action, entityType, entityId, outcome, changes: [{field, before, after, redacted}], requestId}], totalPage}`, page size 100.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/sixpack/it/AuditApiIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.FieldChange;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class AuditApiIntegrationTest extends IntegrationTest {

    @Autowired
    AuditTrail audit;

    private void seed(AuditAction action, String type, String id, String actor) {
        audit.record(AuditEvent.of(action).on(type, id).by(actor, "ADMIN")
                .changing(List.of(FieldChange.of("price", 1.0, 2.0))));
    }

    private ResultActions search(String token, String... params) throws Exception {
        var request = get("/api/v1/admin/audit").header("Authorization", bearer(token));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void onlyAdminsCanReadTheLog() throws Exception {
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized());
        search(register("curious@test.dev")).andExpect(status().isForbidden());
        search(adminToken()).andExpect(status().isOk());
    }

    @Test
    void listsNewestFirstAndFiltersByEveryField() throws Exception {
        String admin = adminToken();
        seed(AuditAction.PRODUCT_CREATED, "product", "p1", "a1");
        seed(AuditAction.PRODUCT_UPDATED, "product", "p1", "a1");
        seed(AuditAction.ORDER_PLACED, "order", "o1", "a2");

        search(admin, "entityType", "product").andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].action").value("PRODUCT_UPDATED"));
        search(admin, "entityType", "product", "entityId", "p1", "action", "product_created")
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].changes[0].field").value("price"))
                .andExpect(jsonPath("$.entries[0].changes[0].after").value(2.0));
        search(admin, "actorId", "a2").andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].entityId").value("o1"));
        search(admin, "outcome", "FAILURE").andExpect(jsonPath("$.entries", hasSize(0)));
        search(admin, "action", "ORDER_PLACED", "outcome", "success")
                .andExpect(jsonPath("$.entries", hasSize(1)));
    }

    @Test
    void filtersByTimeRange() throws Exception {
        String admin = adminToken();
        seed(AuditAction.PRODUCT_CREATED, "product", "p1", "a1");

        search(admin, "from", Instant.now().minusSeconds(3600).toString(), "action", "PRODUCT_CREATED")
                .andExpect(jsonPath("$.entries", hasSize(1)));
        search(admin, "from", Instant.now().plusSeconds(3600).toString(), "action", "PRODUCT_CREATED")
                .andExpect(jsonPath("$.entries", hasSize(0)));
        search(admin, "to", Instant.now().minusSeconds(3600).toString(), "action", "PRODUCT_CREATED")
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    void pagesInHundreds() throws Exception {
        String admin = adminToken();
        for (int i = 0; i < 105; i++) {
            seed(AuditAction.PRODUCT_UPDATED, "product", "p" + i, "a1");
        }

        search(admin, "action", "PRODUCT_UPDATED").andExpect(jsonPath("$.entries", hasSize(100)))
                .andExpect(jsonPath("$.totalPage").value(2));
        search(admin, "action", "PRODUCT_UPDATED", "page", "2").andExpect(jsonPath("$.entries", hasSize(5)));
        search(admin, "action", "PRODUCT_UPDATED", "page", "9").andExpect(jsonPath("$.entries", hasSize(0)));
        search(admin, "action", "PRODUCT_UPDATED", "page", "0").andExpect(jsonPath("$.entries", hasSize(100)));
    }

    @Test
    void badFiltersAreRejectedNotCrashed() throws Exception {
        String admin = adminToken();
        search(admin, "action", "NOT_A_THING").andExpect(status().isBadRequest());
        search(admin, "outcome", "MAYBE").andExpect(status().isBadRequest());
        search(admin, "from", "yesterday").andExpect(status().isBadRequest());
        search(admin, "page", "abc").andExpect(status().isBadRequest());
    }

    @Test
    void neverExposesHashesOrIpEvidence() throws Exception {
        String admin = adminToken();
        audit.record(AuditEvent.of(AuditAction.LOGIN_FAILED).failed().aboutSubject("x@test.dev"));

        String body = search(admin, "action", "LOGIN_FAILED").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("ipHash", "subjectHash", "userAgent");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -Dtest=AuditApiIntegrationTest test`
Expected: FAIL (404 or 405 for `/api/v1/admin/audit`; `anyRequest().authenticated()` currently lets a customer through).

- [ ] **Step 3: Add the controller**

Create `backend/src/main/java/com/sixpack/audit/web/AuditController.java`:

```java
package com.sixpack.audit.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent.Outcome;
import com.sixpack.audit.FieldChange;
import com.sixpack.audit.domain.AuditEntry;
import com.sixpack.audit.domain.AuditQuery;
import com.sixpack.audit.domain.AuditRepository;
import com.sixpack.shared.ApiException;
import com.sixpack.shared.PageResult;
import com.sixpack.shared.web.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the audit log for admins. There is deliberately no write endpoint. */
@RestController
@RequestMapping("/api/v1/admin/audit")
class AuditController {

    static final int PAGE_SIZE = 100;

    /** Hashes and request evidence stay in the database; they are not useful to read and not worth exposing. */
    record EntryView(@JsonProperty("_id") String id, Instant at, String actorId, String actorRole, String action,
                     String entityType, String entityId, String outcome, List<FieldChange> changes,
                     String requestId) {
        static EntryView from(AuditEntry e) {
            return new EntryView(e.id(), e.at(), e.actorId(), e.actorRole(), e.action().name(), e.entityType(),
                    e.entityId(), e.outcome().name(), e.changes(), e.requestId());
        }
    }

    private final AuditRepository entries;

    AuditController(AuditRepository entries) {
        this.entries = entries;
    }

    @GetMapping
    Map<String, Object> search(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") int page) {
        PageResult<AuditEntry> result = entries.search(new AuditQuery(blankToNull(entityType),
                blankToNull(entityId), blankToNull(actorId),
                blankToNull(action) == null ? null : AuditAction.parse(action),
                blankToNull(outcome) == null ? null : parseOutcome(outcome), from, to, Math.max(page, 1), PAGE_SIZE));
        long totalPage = (result.total() + PAGE_SIZE - 1) / PAGE_SIZE;
        return ApiResponse.of("entries", result.items().stream().map(EntryView::from).toList(),
                "totalPage", totalPage);
    }

    private static Outcome parseOutcome(String value) {
        try {
            return Outcome.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("outcome: must be success or failure");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
```

- [ ] **Step 4: Restrict the route to admins**

In `SecurityConfig.java`, add this rule immediately before the `// dashboard` comment:

```java
                        // audit log
                        .requestMatchers("/api/v1/admin/**").hasRole(ADMIN)
```

- [ ] **Step 5: Run the tests**

Run: `cd backend && mvn -q -Dtest='AuditApiIntegrationTest,AuthorizationIntegrationTest' test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(audit): admin-only audit search endpoint" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Audit catalogue, coupon and order writes

**Files:**
- Create: `backend/src/main/java/com/sixpack/product/application/CatalogAudit.java`
- Create: `backend/src/main/java/com/sixpack/coupon/application/CouponAudit.java`
- Modify: `ProductService.java`, `CategoryService.java` (product module); `CouponService.java`; `OrderService.java`, `CheckoutService.java`
- Test: `backend/src/test/java/com/sixpack/it/AuditCatalogIntegrationTest.java`, `backend/src/test/java/com/sixpack/it/AuditOrdersIntegrationTest.java`

**Interfaces:**
- Consumes: `AuditTrail`, `AuditEvent`, `AuditAction`, `Changes`.
- Produces: audit entries `PRODUCT_*`, `CATEGORY_*`, `COUPON_*`, `ORDER_PLACED`, `ORDER_STATUS_CHANGED`, `ORDER_DELETED`.

- [ ] **Step 1: Write the failing catalogue and coupon tests**

Create `backend/src/test/java/com/sixpack/it/AuditCatalogIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

class AuditCatalogIntegrationTest extends IntegrationTest {

    @Test
    void productCreateUpdateAndDeleteAreAudited() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        String id = createProduct(admin, "Lamp", 400, 3, "home");

        Document created = auditEntries("PRODUCT_CREATED").get(0);
        assertThat(created.getString("entityType")).isEqualTo("product");
        assertThat(created.getString("entityId")).isEqualTo(id);
        assertThat(created.getString("actorId")).isEqualTo(adminId);
        assertThat(created.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(changeOf(created, "price").get("after")).isEqualTo(400.0);
        assertThat(changeOf(created, "name").get("after")).isEqualTo("Lamp");

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/product/" + id).param("price", "450")
                        .param("stock", "3").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document updated = auditEntries("PRODUCT_UPDATED").get(0);
        assertThat(updated.getList("changes", Document.class)).extracting(c -> c.getString("field"))
                .containsExactly("price"); // stock was resubmitted unchanged, so it is not listed
        assertThat(changeOf(updated, "price").get("before")).isEqualTo(400.0);
        assertThat(changeOf(updated, "price").get("after")).isEqualTo(450.0);

        mvc.perform(delete("/api/v1/product/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document deleted = auditEntries("PRODUCT_DELETED").get(0);
        assertThat(deleted.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(deleted, "name").get("before")).isEqualTo("Lamp");
    }

    @Test
    void aRefusedProductWriteLeavesNoEntry() throws Exception {
        String admin = adminToken();
        mvc.perform(delete("/api/v1/product/000000000000000000000000").header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound());
        assertThat(auditEntries("PRODUCT_DELETED")).isEmpty();
    }

    @Test
    void categoryLifecycleIsAudited() throws Exception {
        String admin = adminToken();
        String id = body(mvc.perform(multipart("/api/v1/category/new").param("name", "Garden")
                        .header("Authorization", bearer(admin))).andExpect(status().isCreated()).andReturn())
                .get("category").get("_id").asText();
        Document created = auditEntries("CATEGORY_CREATED").get(0);
        assertThat(created.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(created, "name").get("after")).isEqualTo("Garden");

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/category/" + id).param("name", "Garden & Patio")
                .header("Authorization", bearer(admin))).andExpect(status().isOk());
        Document updated = auditEntries("CATEGORY_UPDATED").get(0);
        assertThat(changeOf(updated, "name").get("before")).isEqualTo("Garden");
        assertThat(changeOf(updated, "name").get("after")).isEqualTo("Garden & Patio");

        mvc.perform(delete("/api/v1/category/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditEntries("CATEGORY_DELETED").get(0).getString("entityId")).isEqualTo(id);
    }

    @Test
    void couponLifecycleIsAudited() throws Exception {
        String admin = adminToken();
        String id = body(mvc.perform(post("/api/v1/payment/coupon/new").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("coupon", "SAVE100", "amount", 100))))
                .andExpect(status().isCreated()).andReturn()).get("coupon").get("_id").asText();
        Document created = auditEntries("COUPON_CREATED").get(0);
        assertThat(created.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(created, "code").get("after")).isEqualTo("SAVE100");
        assertThat(changeOf(created, "type").get("after")).isEqualTo("FLAT");

        mvc.perform(put("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", 150))))
                .andExpect(status().isOk());
        Document updated = auditEntries("COUPON_UPDATED").get(0);
        assertThat(changeOf(updated, "amount").get("before")).isEqualTo(100.0);
        assertThat(changeOf(updated, "amount").get("after")).isEqualTo(150.0);

        mvc.perform(delete("/api/v1/payment/coupon/" + id).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditEntries("COUPON_DELETED").get(0).getString("entityId")).isEqualTo(id);
    }
}
```

Create `backend/src/test/java/com/sixpack/it/AuditOrdersIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.bson.Document;
import org.junit.jupiter.api.Test;

class AuditOrdersIntegrationTest extends IntegrationTest {

    @Test
    void placingAdvancingAndDeletingAnOrderAreAudited() throws Exception {
        String admin = adminToken();
        String product = createProduct(admin, "Book", 300, 5, "books");
        String customer = register("orders-audit@test.dev");
        String customerId = userId(customer);

        String orderId = placeOrder(customer, product);

        Document placed = auditEntries("ORDER_PLACED").get(0);
        assertThat(placed.getString("entityType")).isEqualTo("order");
        assertThat(placed.getString("entityId")).isEqualTo(orderId);
        assertThat(placed.getString("actorId")).isEqualTo(customerId);
        assertThat(placed.getString("actorRole")).isEqualTo("USER");
        assertThat(changeOf(placed, "status").get("after")).isEqualTo("PROCESSING");
        assertThat(changeOf(placed, "total").get("after")).isNotNull();

        mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document advanced = auditEntries("ORDER_STATUS_CHANGED").get(0);
        assertThat(advanced.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(advanced.getString("entityId")).isEqualTo(orderId);
        assertThat(changeOf(advanced, "status").get("before")).isEqualTo("PROCESSING");
        assertThat(changeOf(advanced, "status").get("after")).isEqualTo("SHIPPED");

        mvc.perform(delete("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        assertThat(auditEntries("ORDER_DELETED").get(0).getString("entityId")).isEqualTo(orderId);
    }

    @Test
    void advancingADeliveredOrderRecordsNothing() throws Exception {
        String admin = adminToken();
        String customer = register("delivered-audit@test.dev");
        String orderId = placeOrder(customer, createProduct(admin, "Pen", 50, 5, "stationery"));
        for (int i = 0; i < 3; i++) { // processing → shipped → delivered → (no-op)
            mvc.perform(put("/api/v1/order/" + orderId).header("Authorization", bearer(admin)))
                    .andExpect(status().isOk());
        }
        assertThat(auditEntries("ORDER_STATUS_CHANGED")).hasSize(2);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && mvn -q -Dtest='AuditCatalogIntegrationTest,AuditOrdersIntegrationTest' test`
Expected: FAIL (`IndexOutOfBounds` / empty entry lists: nothing is recorded yet).

- [ ] **Step 3: Add the product and category snapshots**

Create `backend/src/main/java/com/sixpack/product/application/CatalogAudit.java`:

```java
package com.sixpack.product.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.product.domain.Category;
import com.sixpack.product.domain.Product;
import com.sixpack.product.domain.ProductDetails;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the audit log may say about products and categories. A field not named in a policy is never logged, so
 * adding a field to {@link Product} does not change the log until it is added here on purpose.
 */
final class CatalogAudit {

    static final FieldPolicy PRODUCT = Changes.policy(List.of("name", "price", "stock", "category", "photo", "slug",
            "sku", "brand", "mrp", "taxRate", "hsn", "status", "tags", "categoryId"), List.of());

    static final FieldPolicy CATEGORY = Changes.policy(List.of("name", "slug", "parentId", "image"), List.of());

    private CatalogAudit() {
    }

    static Map<String, Object> view(Product p) {
        ProductDetails d = p.details();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("price", p.price());
        m.put("stock", p.stock());
        m.put("category", p.category());
        m.put("photo", p.photo());
        m.put("slug", d.slug());
        m.put("sku", d.sku());
        m.put("brand", d.brand());
        m.put("mrp", d.mrp());
        m.put("taxRate", d.taxRate());
        m.put("hsn", d.hsn());
        m.put("status", d.status() == null ? null : d.status().name());
        m.put("tags", d.tags());
        m.put("categoryId", d.categoryId());
        return m;
    }

    static Map<String, Object> view(Category c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("slug", c.slug());
        m.put("parentId", c.parentId());
        m.put("image", c.image());
        return m;
    }
}
```

- [ ] **Step 4: Instrument `ProductService`**

In `ProductService.java` add imports:

```java
import com.sixpack.audit.AuditAction;
import com.sixpack.audit.AuditEvent;
import com.sixpack.audit.AuditTrail;
import com.sixpack.audit.Changes;
import java.util.Map;
```

Add the field and constructor parameter:

```java
    private final CategoryRepository categories;
    private final AuditTrail audit;

    ProductService(ProductRepository products, FileStorage storage, ApplicationEventPublisher events,
                   ProductProperties props, CategoryRepository categories, AuditTrail audit) {
        this.products = products;
        this.storage = storage;
        this.events = events;
        this.props = props;
        this.categories = categories;
        this.audit = audit;
    }
```

In `create`, directly after `events.publishEvent(new ProductChangedEvent());` (inside the `try`, before `return saved;`):

```java
            audit.record(AuditEvent.of(AuditAction.PRODUCT_CREATED).on("product", saved.id())
                    .changing(Changes.diff(Map.of(), CatalogAudit.view(saved), CatalogAudit.PRODUCT)));
```

In `update`, directly after `events.publishEvent(new ProductChangedEvent());` and before `return saved;`:

```java
        audit.record(AuditEvent.of(AuditAction.PRODUCT_UPDATED).on("product", id)
                .changing(Changes.diff(CatalogAudit.view(current), CatalogAudit.view(saved), CatalogAudit.PRODUCT)));
```

In `delete`, directly after `events.publishEvent(new ProductChangedEvent());`:

```java
        audit.record(AuditEvent.of(AuditAction.PRODUCT_DELETED).on("product", id)
                .changing(Changes.diff(CatalogAudit.view(current), Map.of(), CatalogAudit.PRODUCT)));
```

- [ ] **Step 5: Instrument `CategoryService`**

In `CategoryService.java` add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.audit.Changes`, `java.util.Map`. Add the field and constructor parameter:

```java
    private final FileStorage storage;
    private final AuditTrail audit;

    CategoryService(CategoryRepository categories, ProductRepository products, FileStorage storage,
                    AuditTrail audit) {
        this.categories = categories;
        this.products = products;
        this.storage = storage;
        this.audit = audit;
    }
```

In `create`, replace `return categories.save(new Category(null, name, freeSlug(Slugs.of(name)), parentId, image, null));` with:

```java
            Category saved = categories.save(new Category(null, name, freeSlug(Slugs.of(name)), parentId, image, null));
            audit.record(AuditEvent.of(AuditAction.CATEGORY_CREATED).on("category", saved.id())
                    .changing(Changes.diff(Map.of(), CatalogAudit.view(saved), CatalogAudit.CATEGORY)));
            return saved;
```

In `update`, after the `if (current.image() != null && …) { storage.delete(…); }` block and before `return saved;`:

```java
        audit.record(AuditEvent.of(AuditAction.CATEGORY_UPDATED).on("category", id)
                .changing(Changes.diff(CatalogAudit.view(current), CatalogAudit.view(saved),
                        CatalogAudit.CATEGORY)));
```

In `delete`, after the `if (current.image() != null) { storage.delete(current.image()); }` block (end of the method):

```java
        audit.record(AuditEvent.of(AuditAction.CATEGORY_DELETED).on("category", id)
                .changing(Changes.diff(CatalogAudit.view(current), Map.of(), CatalogAudit.CATEGORY)));
```

- [ ] **Step 6: Add the coupon snapshot and instrument `CouponService`**

Create `backend/src/main/java/com/sixpack/coupon/application/CouponAudit.java`:

```java
package com.sixpack.coupon.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.coupon.domain.Coupon;
import com.sixpack.coupon.domain.CouponRules;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the audit log may say about coupons. {@code usedCount} is deliberately absent: redemptions are not edits. */
final class CouponAudit {

    static final FieldPolicy POLICY = Changes.policy(List.of("code", "type", "amount", "maxDiscount",
            "minOrderValue", "startsAt", "expiresAt", "usageLimit", "perUserLimit", "active"), List.of());

    private CouponAudit() {
    }

    static Map<String, Object> view(Coupon c) {
        CouponRules r = c.rules();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", c.code());
        m.put("type", r.type().name());
        m.put("amount", c.amount());
        m.put("maxDiscount", r.maxDiscount());
        m.put("minOrderValue", r.minOrderValue());
        m.put("startsAt", r.startsAt());
        m.put("expiresAt", r.expiresAt());
        m.put("usageLimit", r.usageLimit());
        m.put("perUserLimit", r.perUserLimit());
        m.put("active", r.active());
        return m;
    }
}
```

In `CouponService.java` add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.audit.Changes`, `java.util.Map`. Replace the field and constructor:

```java
    private final CouponRepository coupons;
    private final AuditTrail audit;

    CouponService(CouponRepository coupons, AuditTrail audit) {
        this.coupons = coupons;
        this.audit = audit;
    }
```

In `create`, replace `return coupons.save(new Coupon(null, code, c.amount(), rules, 0));` with:

```java
        Coupon saved = coupons.save(new Coupon(null, code, c.amount(), rules, 0));
        audit.record(AuditEvent.of(AuditAction.COUPON_CREATED).on("coupon", saved.id())
                .changing(Changes.diff(Map.of(), CouponAudit.view(saved), CouponAudit.POLICY)));
        return saved;
```

In `update`, replace `return coupons.save(current.withRules(amount, merged));` with:

```java
        Coupon saved = coupons.save(current.withRules(amount, merged));
        audit.record(AuditEvent.of(AuditAction.COUPON_UPDATED).on("coupon", id)
                .changing(Changes.diff(CouponAudit.view(current), CouponAudit.view(saved), CouponAudit.POLICY)));
        return saved;
```

Replace `delete` with:

```java
    public void delete(String id) {
        Coupon current = coupons.findById(id).orElseThrow(() -> ApiException.notFound("Coupon not found"));
        if (!coupons.deleteById(id)) {
            throw ApiException.notFound("Coupon not found");
        }
        audit.record(AuditEvent.of(AuditAction.COUPON_DELETED).on("coupon", id)
                .changing(Changes.diff(CouponAudit.view(current), Map.of(), CouponAudit.POLICY)));
    }
```

- [ ] **Step 7: Instrument orders**

In `OrderService.java` add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.audit.FieldChange`. Replace the field/constructor and the two write methods:

```java
    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;

    OrderService(OrderRepository orders, ApplicationEventPublisher events, AuditTrail audit) {
        this.orders = orders;
        this.events = events;
        this.audit = audit;
    }
```

```java
    public Order advance(String id, String actorId) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        OrderStatus next = order.status().next();
        if (next == order.status()) {
            return order; // already delivered: nothing to record
        }
        Order saved = orders.save(order.withStatus(next, actorId, Instant.now()));
        events.publishEvent(new OrderChangedEvent(id));
        audit.record(AuditEvent.of(AuditAction.ORDER_STATUS_CHANGED).on("order", id)
                .changing(List.of(FieldChange.of("status", order.status().name(), next.name()))));
        return saved;
    }

    public void delete(String id) {
        orders.findById(id).orElseThrow(() -> ApiException.notFound("Order not found"));
        orders.deleteById(id);
        events.publishEvent(new OrderChangedEvent(id));
        audit.record(AuditEvent.of(AuditAction.ORDER_DELETED).on("order", id));
    }
```

In `CheckoutService.java` add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.audit.FieldChange` (`java.util.List` is already imported). Add the field and constructor parameter:

```java
    private final UserDirectory users;
    private final AuditTrail audit;

    CheckoutService(ProductCatalog catalog, CouponLookup coupons, PaymentGateway gateway,
                    PaymentIntentRepository intents, OrderRepository orders, ConsentLedger consents,
                    ApplicationEventPublisher events, MeterRegistry meters, UserDirectory users,
                    AuditTrail audit) {
        // … existing assignments unchanged …
        this.users = users;
        this.audit = audit;
    }
```

Directly after `events.publishEvent(new OrderChangedEvent(order.id()));` in `confirm`:

```java
        audit.record(AuditEvent.of(AuditAction.ORDER_PLACED).on("order", order.id())
                .changing(List.of(FieldChange.of("status", null, OrderStatus.PROCESSING.name()),
                        FieldChange.of("total", null, intent.quote().total()))));
```

- [ ] **Step 8: Run the tests**

Run: `cd backend && mvn -q -Dtest='AuditCatalogIntegrationTest,AuditOrdersIntegrationTest,ProductIntegrationTest,CategoryIntegrationTest,CouponAdminIntegrationTest,CheckoutIntegrationTest,ModularityTests' test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add backend
git commit -m "feat(audit): record catalogue, coupon and order changes" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Audit user, authentication and consent events

**Files:**
- Create: `backend/src/main/java/com/sixpack/user/application/UserAudit.java`
- Modify: `AuthService.java`, `UserAdminService.java`, `AccountService.java`, `ProfileService.java`, `PasswordResetService.java` (user module); `ConsentService.java`
- Test: `backend/src/test/java/com/sixpack/it/AuditUsersIntegrationTest.java`

**Interfaces:**
- Consumes: `AuditTrail`, `AuditEvent.by`, `AuditEvent.aboutSubject`, `Changes`, `PasswordResetTokenRepository.save(PasswordResetToken)`.
- Produces: audit entries `USER_REGISTERED`, `LOGIN_*`, `PASSWORD_RESET_*`, `USER_ROLE_CHANGED`, `USER_STATUS_CHANGED`, `USER_ERASED`, `ACCOUNT_DELETED_SELF`, `PROFILE_UPDATED`, `ADDRESS_*`, `CONSENT_CHANGED`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/sixpack/it/AuditUsersIntegrationTest.java`:

```java
package com.sixpack.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sixpack.user.domain.PasswordResetToken;
import com.sixpack.user.domain.PasswordResetTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AuditUsersIntegrationTest extends IntegrationTest {

    @Autowired
    PasswordResetTokenRepository resetTokens;

    private String wholeLog() {
        List<String> docs = new ArrayList<>();
        mongo.getCollection("audit_log").find().forEach(d -> docs.add(d.toJson()));
        return String.join("\n", docs);
    }

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String token, Object body) throws Exception {
        if (token != null) {
            request = request.header("Authorization", bearer(token));
        }
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private static final Map<String, Object> ADDRESS = Map.of("name", "Asha Rao", "phone", "9876543210",
            "line1", "12 MG Road", "city", "Bengaluru", "state", "Karnataka", "country", "India",
            "pinCode", "560001", "isDefault", true);

    @Test
    void registrationAndLoginAreAuditedWithoutTheEmail() throws Exception {
        String token = register("reg-audit@test.dev");
        String id = userId(token);

        Document registered = auditEntries("USER_REGISTERED").get(0);
        assertThat(registered.getString("actorId")).isEqualTo(id);
        assertThat(registered.getString("actorRole")).isEqualTo("USER");
        assertThat(registered.getString("entityId")).isEqualTo(id);

        json(post("/api/v1/user/login"), null, Map.of("email", "reg-audit@test.dev", "password", "password123"))
                .andExpect(status().isOk());
        Document login = auditEntries("LOGIN_SUCCEEDED").get(0);
        assertThat(login.getString("actorId")).isEqualTo(id);
        assertThat(login.getString("outcome")).isEqualTo("SUCCESS");

        assertThat(wholeLog()).doesNotContain("reg-audit@test.dev", "Test User", "password123");
    }

    @Test
    void aFailedLoginIsAuditedAsAnonymousWithOnlyAHashOfTheEmail() throws Exception {
        register("target@test.dev");
        json(post("/api/v1/user/login"), null, Map.of("email", "Target@test.dev", "password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        json(post("/api/v1/user/login"), null, Map.of("email", "nobody@test.dev", "password", "wrong-password"))
                .andExpect(status().isUnauthorized());

        List<Document> failures = auditEntries("LOGIN_FAILED");
        assertThat(failures).hasSize(2).allSatisfy(f -> {
            assertThat(f.getString("outcome")).isEqualTo("FAILURE");
            assertThat(f.getString("actorRole")).isEqualTo("ANONYMOUS");
            assertThat(f.getString("actorId")).isNull();
            assertThat(f.getString("subjectHash")).hasSize(64);
        });
        // The unknown email gets no entity; the known one is tied to the account being attacked.
        assertThat(failures.stream().filter(f -> f.getString("entityId") != null)).hasSize(1);
        assertThat(wholeLog().toLowerCase()).doesNotContain("target@test.dev", "nobody@test.dev");
    }

    @Test
    void adminActionsOnUsersAreAudited() throws Exception {
        String admin = adminToken();
        String adminId = userId(admin);
        String customer = register("managed@test.dev");
        String customerId = userId(customer);

        json(put("/api/v1/user/" + customerId), admin, Map.of("status", "suspended")).andExpect(status().isOk());
        Document status = auditEntries("USER_STATUS_CHANGED").get(0);
        assertThat(status.getString("actorId")).isEqualTo(adminId);
        assertThat(status.getString("entityId")).isEqualTo(customerId);
        assertThat(changeOf(status, "status").get("before")).isEqualTo("ACTIVE");
        assertThat(changeOf(status, "status").get("after")).isEqualTo("SUSPENDED");

        json(post("/api/v1/user/login"), null, Map.of("email", "managed@test.dev", "password", "password123"))
                .andExpect(status().isForbidden());
        assertThat(auditEntries("LOGIN_BLOCKED_SUSPENDED").get(0).getString("entityId")).isEqualTo(customerId);

        json(put("/api/v1/user/" + customerId), admin, Map.of("role", "admin")).andExpect(status().isOk());
        Document role = auditEntries("USER_ROLE_CHANGED").get(0);
        assertThat(changeOf(role, "role").get("before")).isEqualTo("USER");
        assertThat(changeOf(role, "role").get("after")).isEqualTo("ADMIN");

        mvc.perform(delete("/api/v1/user/" + customerId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        Document erased = auditEntries("USER_ERASED").get(0);
        assertThat(erased.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(erased.getString("entityId")).isEqualTo(customerId);
    }

    @Test
    void profileEditsRecordThatPersonalFieldsChangedButNotTheirValues() throws Exception {
        String token = register("profile-audit@test.dev");
        String id = userId(token);

        json(put("/api/v1/user/me"), token, Map.of("name", "Zed Zebra", "phone", "+919876501234",
                "locale", "en-IN")).andExpect(status().isOk());

        Document updated = auditEntries("PROFILE_UPDATED").get(0);
        assertThat(updated.getString("entityId")).isEqualTo(id);
        assertThat(changeOf(updated, "name").getBoolean("redacted")).isTrue();
        assertThat(changeOf(updated, "name").get("after")).isNull();
        assertThat(changeOf(updated, "phone").getBoolean("redacted")).isTrue();
        assertThat(changeOf(updated, "locale").getBoolean("redacted")).isFalse();
        assertThat(changeOf(updated, "locale").get("after")).isEqualTo("en-IN");
        assertThat(wholeLog()).doesNotContain("Zed Zebra", "919876501234");
    }

    @Test
    void addressChangesAreAuditedByIdOnly() throws Exception {
        String token = register("addr-audit@test.dev");

        String addressId = body(json(post("/api/v1/user/me/addresses"), token, ADDRESS)
                .andExpect(status().isCreated()).andReturn()).get("address").get("id").asText();
        assertThat(auditEntries("ADDRESS_ADDED").get(0).getString("entityId")).isEqualTo(addressId);

        json(put("/api/v1/user/me/addresses/" + addressId), token, ADDRESS).andExpect(status().isOk());
        assertThat(auditEntries("ADDRESS_UPDATED").get(0).getString("entityId")).isEqualTo(addressId);

        mvc.perform(delete("/api/v1/user/me/addresses/" + addressId).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        assertThat(auditEntries("ADDRESS_DELETED").get(0).getString("entityId")).isEqualTo(addressId);

        assertThat(wholeLog()).doesNotContain("12 MG Road", "560001", "9876543210", "Asha Rao");
    }

    @Test
    void passwordResetIsAudited() throws Exception {
        String token = register("reset-audit@test.dev");
        String id = userId(token);

        json(post("/api/v1/user/forgot-password"), null, Map.of("email", "reset-audit@test.dev"))
                .andExpect(status().isOk());
        json(post("/api/v1/user/forgot-password"), null, Map.of("email", "ghost@test.dev"))
                .andExpect(status().isOk());
        List<Document> requested = auditEntries("PASSWORD_RESET_REQUESTED");
        assertThat(requested).hasSize(2).allSatisfy(r -> assertThat(r.getString("subjectHash")).hasSize(64));
        assertThat(requested.stream().filter(r -> id.equals(r.getString("entityId")))).hasSize(1);

        String raw = "known-reset-token";
        resetTokens.save(new PasswordResetToken(null, id, sha256(raw), Instant.now().plusSeconds(600)));
        json(post("/api/v1/user/reset-password"), null, Map.of("token", raw, "password", "a-new-password-1"))
                .andExpect(status().isOk());
        Document completed = auditEntries("PASSWORD_RESET_COMPLETED").get(0);
        assertThat(completed.getString("actorId")).isEqualTo(id);
        assertThat(completed.getString("entityId")).isEqualTo(id);

        assertThat(wholeLog().toLowerCase()).doesNotContain("reset-audit@test.dev", "ghost@test.dev", raw,
                "a-new-password-1");
    }

    @Test
    void consentDecisionsAreAuditedButRegistrationConsentsAreNot() throws Exception {
        String token = register("consent-audit@test.dev");
        String id = userId(token);
        assertThat(auditEntries("CONSENT_CHANGED")).isEmpty(); // the ledger already records these

        json(post("/api/v1/consent"), token, Map.of("purpose", "marketing_email", "granted", true,
                "source", "settings")).andExpect(status().isOk());

        Document entry = auditEntries("CONSENT_CHANGED").get(0);
        assertThat(entry.getString("actorId")).isEqualTo(id);
        assertThat(changeOf(entry, "purpose").get("after")).isEqualTo("marketing_email");
        assertThat(changeOf(entry, "granted").get("after")).isEqualTo(true);
        assertThat(changeOf(entry, "version").get("after")).isEqualTo("2026-10-16");
    }

    @Test
    void acceptingTheAdminAgreementIsAudited() throws Exception {
        String admin = adminToken();
        Document entry = auditEntries("CONSENT_CHANGED").get(0);
        assertThat(entry.getString("actorRole")).isEqualTo("ADMIN");
        assertThat(entry.getString("actorId")).isEqualTo(userId(admin));
        assertThat(changeOf(entry, "purpose").get("after")).isEqualTo("admin_aup");
        assertThat(changeOf(entry, "version").get("after")).isEqualTo("2026-10-18");
    }

    @Test
    void deletingYourOwnAccountLeavesNoPersonalValuesInTheLog() throws Exception {
        String token = register("erase-audit@test.dev");
        String id = userId(token);
        json(put("/api/v1/user/me"), token, Map.of("name", "Erasable Name", "phone", "+919000000001"))
                .andExpect(status().isOk());

        json(delete("/api/v1/user/me"), token, Map.of("password", "password123")).andExpect(status().isOk());

        Document deleted = auditEntries("ACCOUNT_DELETED_SELF").get(0);
        assertThat(deleted.getString("actorId")).isEqualTo(id);
        // The trail survives erasure, holding only the opaque id.
        assertThat(mongo.getCollection("audit_log").countDocuments(new Document("actorId", id)))
                .isGreaterThan(2);
        assertThat(wholeLog().toLowerCase()).doesNotContain("erase-audit@test.dev", "erasable name", "9000000001");
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -Dtest=AuditUsersIntegrationTest test`
Expected: FAIL (no entries are recorded yet).

- [ ] **Step 3: Add the user snapshots and policies**

Create `backend/src/main/java/com/sixpack/user/application/UserAudit.java`:

```java
package com.sixpack.user.application;

import com.sixpack.audit.Changes;
import com.sixpack.audit.Changes.FieldPolicy;
import com.sixpack.user.domain.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the audit log may say about users. Name and phone are personal data, so the log only says they changed.
 * Email, DOB, addresses and the password hash are not in any policy and so are never logged.
 */
final class UserAudit {

    static final FieldPolicy PROFILE = Changes.policy(List.of("locale"), List.of("name", "phone"));
    static final FieldPolicy ROLE = Changes.policy(List.of("role"), List.of());
    static final FieldPolicy STATUS = Changes.policy(List.of("status"), List.of());

    private UserAudit() {
    }

    static Map<String, Object> profile(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", u.name());
        m.put("phone", u.details().phone());
        m.put("locale", u.details().locale());
        return m;
    }
}
```

- [ ] **Step 4: Instrument `AuthService`**

In `AuthService.java` add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`. Add the field and constructor parameter:

```java
    private final MeterRegistry meters;
    private final AuditTrail audit;

    AuthService(UserRepository users, PasswordEncoder encoder, TokenService tokens, FileStorage storage,
                ConsentLedger consents, MeterRegistry meters, AuditTrail audit) {
        // … existing assignments unchanged …
        this.meters = meters;
        this.audit = audit;
    }
```

In `register`, directly after `consents.recordRegistration(…);`:

```java
        audit.record(AuditEvent.of(AuditAction.USER_REGISTERED).on("user", saved.id())
                .by(saved.id(), saved.role().name()));
```

Replace `login` with:

```java
    public AuthResult login(String email, String password) {
        User user = users.findByEmail(normalizeEmail(email)).orElse(null);
        if (user == null || user.passwordHash() == null || !encoder.matches(password, user.passwordHash())) {
            meters.counter("sixpack.auth.login", "result", "failure").increment();
            AuditEvent failure = AuditEvent.of(AuditAction.LOGIN_FAILED).failed().aboutSubject(email);
            audit.record(user == null ? failure : failure.on("user", user.id()));
            throw ApiException.unauthorized("Invalid Email or Password");
        }
        if (user.details().status() == UserStatus.SUSPENDED) {
            meters.counter("sixpack.auth.login", "result", "suspended").increment();
            audit.record(AuditEvent.of(AuditAction.LOGIN_BLOCKED_SUSPENDED).failed().on("user", user.id())
                    .by(user.id(), user.role().name()));
            throw ApiException.forbidden("Your account is suspended");
        }
        users.touchLastLogin(user.id(), Instant.now());
        User fresh = users.findById(user.id()).orElse(user);
        meters.counter("sixpack.auth.login", "result", "success").increment();
        audit.record(AuditEvent.of(AuditAction.LOGIN_SUCCEEDED).on("user", fresh.id())
                .by(fresh.id(), fresh.role().name()));
        return new AuthResult(issue(fresh), fresh);
    }
```

- [ ] **Step 5: Instrument `UserAdminService`, `AccountService`, `ProfileService`**

`UserAdminService.java`: add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.audit.Changes`, `java.util.Map`. Replace the fields, constructor and the three write methods:

```java
    private final UserRepository users;
    private final AccountService accounts;
    private final AuditTrail audit;

    UserAdminService(UserRepository users, AccountService accounts, AuditTrail audit) {
        this.users = users;
        this.accounts = accounts;
        this.audit = audit;
    }
```

```java
    public void changeRole(String id, Role role, String actingAdminId) {
        if (id.equals(actingAdminId) && role != Role.ADMIN) {
            throw ApiException.badRequest("You cannot remove your own admin role");
        }
        User before = get(id);
        users.save(before.withRole(role));
        audit.record(AuditEvent.of(AuditAction.USER_ROLE_CHANGED).on("user", id)
                .changing(Changes.diff(Map.of("role", before.role().name()), Map.of("role", role.name()),
                        UserAudit.ROLE)));
    }

    public void changeStatus(String id, UserStatus status, String actingAdminId) {
        if (id.equals(actingAdminId) && status != UserStatus.ACTIVE) {
            throw ApiException.badRequest("You cannot suspend your own account");
        }
        User before = get(id);
        users.setStatus(id, status);
        audit.record(AuditEvent.of(AuditAction.USER_STATUS_CHANGED).on("user", id)
                .changing(Changes.diff(Map.of("status", before.details().status().name()),
                        Map.of("status", status.name()), UserAudit.STATUS)));
    }

    public void delete(String id, String actingAdminId) {
        if (id.equals(actingAdminId)) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        accounts.erase(get(id));
        audit.record(AuditEvent.of(AuditAction.USER_ERASED).on("user", id));
    }
```

`AccountService.java`: add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`; add the field `private final AuditTrail audit;` and constructor parameter `AuditTrail audit` (assign `this.audit = audit;`). At the end of `deleteOwnAccount`, after `erase(user);`:

```java
        audit.record(AuditEvent.of(AuditAction.ACCOUNT_DELETED_SELF).on("user", userId));
```

`ProfileService.java`: add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.audit.Changes`. Replace the field/constructor and the write methods:

```java
    private final UserRepository users;
    private final AuditTrail audit;

    ProfileService(UserRepository users, AuditTrail audit) {
        this.users = users;
        this.audit = audit;
    }

    public User updateProfile(String userId, ProfileCommand c) {
        User before = user(userId);
        users.updateProfile(userId, trimOrNull(c.name()), trimOrNull(c.phone()), trimOrNull(c.locale()));
        User after = user(userId);
        audit.record(AuditEvent.of(AuditAction.PROFILE_UPDATED).on("user", userId)
                .changing(Changes.diff(UserAudit.profile(before), UserAudit.profile(after), UserAudit.PROFILE)));
        return after;
    }

    public Address addAddress(String userId, AddressCommand c) {
        Address created = c.toAddress(UUID.randomUUID().toString());
        users.replaceAddresses(userId, AddressBook.add(addresses(userId), created));
        audit.record(AuditEvent.of(AuditAction.ADDRESS_ADDED).on("address", created.id()));
        return created;
    }

    public Address updateAddress(String userId, String addressId, AddressCommand c) {
        List<Address> next = AddressBook.update(addresses(userId), addressId, c.toAddress(addressId));
        users.replaceAddresses(userId, next);
        audit.record(AuditEvent.of(AuditAction.ADDRESS_UPDATED).on("address", addressId));
        return next.stream().filter(a -> a.id().equals(addressId)).findFirst().orElseThrow();
    }

    public void deleteAddress(String userId, String addressId) {
        users.replaceAddresses(userId, AddressBook.remove(addresses(userId), addressId));
        audit.record(AuditEvent.of(AuditAction.ADDRESS_DELETED).on("address", addressId));
    }
```

(Leave `addresses`, `user` and `trimOrNull` unchanged.)

- [ ] **Step 6: Instrument `PasswordResetService`**

Add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.user.domain.User`, `java.util.Optional`. Add the field `private final AuditTrail audit;` and the constructor parameter `AuditTrail audit` (assign it). Replace `requestReset` and `reset`:

```java
    /** Always succeeds from the caller's view so the endpoint cannot be used to discover accounts. */
    public void requestReset(String email) {
        String normalized = AuthService.normalizeEmail(email);
        Optional<User> found = users.findByEmail(normalized);
        found.ifPresent(this::sendResetLink);
        AuditEvent event = AuditEvent.of(AuditAction.PASSWORD_RESET_REQUESTED).aboutSubject(normalized);
        audit.record(found.map(u -> event.on("user", u.id())).orElse(event));
    }

    private void sendResetLink(User user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        tokens.deleteByUserId(user.id());
        tokens.save(new PasswordResetToken(null, user.id(), sha256(token), Instant.now().plus(TOKEN_TTL)));
        String base = props.frontendUrl() == null ? "" : props.frontendUrl().replaceAll("/+$", "");
        mail.sendPasswordReset(user.email(), user.name(), base + "/forgot-password?token=" + token);
    }

    public void reset(String token, String newPassword) {
        PasswordResetToken stored = tokens.consumeByHash(sha256(token))
                .filter(t -> t.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.badRequest("Reset link is invalid or has expired"));
        var user = users.findById(stored.userId())
                .orElseThrow(() -> ApiException.badRequest("Reset link is invalid or has expired"));
        users.save(user.withPasswordHash(encoder.encode(newPassword)));
        audit.record(AuditEvent.of(AuditAction.PASSWORD_RESET_COMPLETED).on("user", user.id())
                .by(user.id(), user.role().name()));
    }
```

- [ ] **Step 7: Instrument consent decisions**

In `ConsentService.java` add imports `com.sixpack.audit.AuditAction`, `com.sixpack.audit.AuditEvent`, `com.sixpack.audit.AuditTrail`, `com.sixpack.audit.FieldChange`. Add the field and constructor parameter:

```java
    private final EvidenceHasher hasher;
    private final AuditTrail audit;

    ConsentService(ConsentRepository records, LegalProperties legal, EvidenceHasher hasher, AuditTrail audit) {
        this.records = records;
        this.legal = legal;
        this.hasher = hasher;
        this.audit = audit;
    }
```

In `decide`, replace `append(userId, purpose, granted, source, evidence);` with:

```java
        append(userId, purpose, granted, source, evidence);
        // The ledger is the legal proof; this entry only puts the decision in the admin audit view.
        audit.record(AuditEvent.of(AuditAction.CONSENT_CHANGED).on("user", userId).changing(List.of(
                FieldChange.of("purpose", null, purpose.wireValue()), FieldChange.of("granted", null, granted),
                FieldChange.of("version", null, currentVersion(purpose)))));
```

(`recordRegistration` is untouched, so registration consents are not duplicated.)

- [ ] **Step 8: Run the tests**

Run: `cd backend && mvn -q -Dtest='AuditUsersIntegrationTest,AuthIntegrationTest,UserProfileIntegrationTest,UserStatusIntegrationTest,ConsentIntegrationTest,AdminAgreementIntegrationTest,ModularityTests' test`
Expected: PASS.

- [ ] **Step 9: Run the whole backend suite**

Run: `cd backend && mvn -q test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add backend
git commit -m "feat(audit): record user, authentication, password-reset and consent events" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Audit log page in the admin app

**Files:**
- Create: `ecommerce-frontend/src/redux/api/auditAPI.ts`
- Create: `ecommerce-frontend/src/pages/admin/audit.tsx`
- Modify: `ecommerce-frontend/src/redux/store.ts`
- Modify: `ecommerce-frontend/src/App.tsx` (lazy import near line 35, route near line 155)
- Modify: `ecommerce-frontend/src/components/admin/AdminSidebar.tsx` (`NAV`)

**Interfaces:**
- Consumes: `GET /api/v1/admin/audit` (Task 5).
- Produces: route `/admin/audit`, hook `useAuditLogQuery`.

- [ ] **Step 1: Add the API slice**

Create `ecommerce-frontend/src/redux/api/auditAPI.ts`:

```ts
import { createApi } from "@reduxjs/toolkit/query/react";
import { authedBaseQuery } from "./baseQuery";

export const AUDIT_ACTIONS = [
  "PRODUCT_CREATED", "PRODUCT_UPDATED", "PRODUCT_DELETED",
  "CATEGORY_CREATED", "CATEGORY_UPDATED", "CATEGORY_DELETED",
  "COUPON_CREATED", "COUPON_UPDATED", "COUPON_DELETED",
  "ORDER_PLACED", "ORDER_STATUS_CHANGED", "ORDER_DELETED",
  "USER_REGISTERED", "USER_ROLE_CHANGED", "USER_STATUS_CHANGED", "USER_ERASED", "ACCOUNT_DELETED_SELF",
  "PROFILE_UPDATED", "ADDRESS_ADDED", "ADDRESS_UPDATED", "ADDRESS_DELETED",
  "LOGIN_SUCCEEDED", "LOGIN_FAILED", "LOGIN_BLOCKED_SUSPENDED",
  "PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_COMPLETED",
  "CONSENT_CHANGED",
] as const;

export type AuditChange = { field: string; before?: unknown; after?: unknown; redacted: boolean };

export type AuditEntry = {
  _id: string;
  at: string;
  actorId: string | null;
  actorRole: "ADMIN" | "USER" | "ANONYMOUS" | "SYSTEM";
  action: (typeof AUDIT_ACTIONS)[number];
  entityType: string | null;
  entityId: string | null;
  outcome: "SUCCESS" | "FAILURE";
  changes: AuditChange[];
  requestId: string | null;
};

export type AuditFilters = {
  entityType?: string;
  action?: string;
  outcome?: string;
  from?: string;
  to?: string;
  page: number;
};

export type AuditResponse = { success: boolean; entries: AuditEntry[]; totalPage: number };

export const auditAPI = createApi({
  reducerPath: "auditApi",
  baseQuery: authedBaseQuery("admin/"),
  endpoints: (builder) => ({
    auditLog: builder.query<AuditResponse, AuditFilters>({
      query: (filters) => ({
        url: "audit",
        // Empty filters are left out rather than sent as blank values.
        params: Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== "" && v !== undefined)),
      }),
    }),
  }),
});

export const { useAuditLogQuery } = auditAPI;
```

In `store.ts` add the import and registration alongside the others:

```ts
import { auditAPI } from "./api/auditAPI";
```
```ts
    [auditAPI.reducerPath]: auditAPI.reducer,
```
```ts
      consentAPI.middleware,
      auditAPI.middleware
```

(the existing last middleware line `consentAPI.middleware` gains a trailing comma).

- [ ] **Step 2: Add the page**

Create `ecommerce-frontend/src/pages/admin/audit.tsx`:

```tsx
import { useState } from "react";
import { CellProps, Column } from "react-table";
import AdminLayout from "../../components/admin/AdminLayout";
import DataTable from "../../components/admin/DataTable";
import { AUDIT_ACTIONS, AuditChange, AuditEntry, useAuditLogQuery } from "../../redux/api/auditAPI";

const ENTITY_TYPES = ["product", "category", "coupon", "order", "user", "address"];

type Row = AuditEntry & { actor: string; entity: string; summary: string };

const show = (value: unknown) =>
  value === null || value === undefined ? "—" : Array.isArray(value) ? value.join(", ") : String(value);

const summarise = (changes: AuditChange[]) =>
  changes
    .map((c) => (c.redacted ? `${c.field} changed` : `${c.field}: ${show(c.before)} → ${show(c.after)}`))
    .join("; ");

const columns: Column<Row>[] = [
  { Header: "When", accessor: "at", width: 170, Cell: ({ value }: CellProps<Row>) => new Date(value).toLocaleString("en-IN") } as Column<Row>,
  { Header: "Who", accessor: "actor", width: 190 },
  { Header: "Action", accessor: "action", width: 210 },
  { Header: "Record", accessor: "entity", width: 230 },
  { Header: "Result", accessor: "outcome", width: 100 },
  { Header: "Changes", accessor: "summary" },
];

const startOfDay = (date: string) => (date ? new Date(`${date}T00:00:00`).toISOString() : "");
const endOfDay = (date: string) => (date ? new Date(`${date}T23:59:59.999`).toISOString() : "");

const AuditLog = () => {
  const [entityType, setEntityType] = useState("");
  const [action, setAction] = useState("");
  const [outcome, setOutcome] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(1);

  const { data, isLoading, isFetching } = useAuditLogQuery({
    entityType,
    action,
    outcome,
    from: startOfDay(from),
    to: endOfDay(to),
    page,
  });

  const rows: Row[] = (data?.entries ?? []).map((e) => ({
    ...e,
    actor: e.actorId ? `${e.actorRole} ${e.actorId.slice(-6)}` : e.actorRole,
    entity: e.entityType ? `${e.entityType} ${e.entityId ?? ""}`.trim() : "—",
    summary: summarise(e.changes),
  }));
  const totalPage = data?.totalPage ?? 1;

  // Changing a filter returns to the first page.
  const filter = (set: (value: string) => void) => (value: string) => {
    set(value);
    setPage(1);
  };

  return (
    <AdminLayout>
      <DataTable
        columns={columns}
        data={rows}
        isLoading={isLoading || isFetching}
        searchPlaceholder="Search this page…"
        emptyText="No audit entries match these filters"
        filters={
          <>
            <select value={entityType} onChange={(e) => filter(setEntityType)(e.target.value)} aria-label="Record type">
              <option value="">All records</option>
              {ENTITY_TYPES.map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
            <select value={action} onChange={(e) => filter(setAction)(e.target.value)} aria-label="Action">
              <option value="">All actions</option>
              {AUDIT_ACTIONS.map((a) => (
                <option key={a} value={a}>{a}</option>
              ))}
            </select>
            <select value={outcome} onChange={(e) => filter(setOutcome)(e.target.value)} aria-label="Result">
              <option value="">Any result</option>
              <option value="SUCCESS">Success</option>
              <option value="FAILURE">Failure</option>
            </select>
            <input type="date" value={from} onChange={(e) => filter(setFrom)(e.target.value)} aria-label="From date" />
            <input type="date" value={to} onChange={(e) => filter(setTo)(e.target.value)} aria-label="To date" />
          </>
        }
        actions={
          <>
            <button type="button" disabled={page <= 1} onClick={() => setPage(page - 1)}>Newer</button>
            <span aria-live="polite">Page {page} of {Math.max(totalPage, 1)}</span>
            <button type="button" disabled={page >= totalPage} onClick={() => setPage(page + 1)}>Older</button>
          </>
        }
      />
    </AdminLayout>
  );
};

export default AuditLog;
```

- [ ] **Step 3: Wire the route and the sidebar**

In `App.tsx`, directly after the line `const Coupon = lazy(() => import("./pages/admin/apps/coupon"));` add:

```tsx
const AuditLog = lazy(() => import("./pages/admin/audit"));
```

and directly after the line `<Route path="/admin/app/coupon" element={<Coupon />} />` add:

```tsx
            <Route path="/admin/audit" element={<AuditLog />} />
```

In `AdminSidebar.tsx` add `LuHistory` to the `react-icons/lu` import list (keep it alphabetical: after `LuBookOpen`) and append to `NAV`:

```tsx
  { url: "/admin/audit", text: "Audit log", Icon: LuHistory },
```

- [ ] **Step 4: Verify the build and lint**

Run: `cd ecommerce-frontend && npm run build && npm run lint`
Expected: both exit 0.

- [ ] **Step 5: Commit**

```bash
git add ecommerce-frontend
git commit -m "feat(audit): admin audit log page" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Documentation, spec alignment and final verification

**Files:**
- Modify: `docs/foundations/compliance-consent.md`, `contracts.md`, `architecture.md`, `requirements.md`, `test-rollout.md`
- Modify: `docs/superpowers/specs/2026-10-18-admin-agreement-and-audit-design.md`
- Modify: `ecommerce-frontend/src/legal/policies.ts` (retention wording)
- Modify: `k8s/` (only if the grep in Step 1 finds the LEGAL variables there)

- [ ] **Step 1: Find every place the other LEGAL variables are configured**

Run: `grep -rn "LEGAL_PRIVACY_VERSION\|CONSENT_IP_SALT" --include='*' -I . 2>/dev/null | grep -v node_modules | grep -v '/target/' | grep -v '/docs/'`
Expected: `.env.example` and `application.yml` (already done) and possibly `k8s/` manifests or `docker-compose.yml`. For each hit outside those two files, add `LEGAL_ADMIN_AUP_VERSION` and `AUDIT_RETENTION` next to it, using the same style as the neighbouring variable. If there are no other hits, nothing to do.

- [ ] **Step 2: Update `compliance-consent.md`**

- In the §9 checklist change `- [ ] 🟢 Admin acceptable-use clickwrap (P8 / C12).` to `- [x] 🟢 Admin acceptable-use clickwrap (P8 / C12): \`admin_aup\` purpose, accepted on first login and re-asked when \`LEGAL_ADMIN_AUP_VERSION\` changes. Admin endpoints return 403 until it is accepted.`
- In the §2 policy table change the P8 row's route from `/admin/legal/aup` to `/legal/admin-aup`.
- In the §6 API table, add: `| \`GET /admin/audit\` | 🛡 | Search the audit log (§10) |`.
- In the §7 retention table add two rows:

```
| Audit log (`audit_log`) | 365 days (`AUDIT_RETENTION`), then deleted by a TTL index. Holds actor ids, record ids and field names; personal values are never stored. | Deleted automatically |
| Attempted-login email hash (`subjectHash`) | Same as the audit log. It is a salted hash, so it is pseudonymous; it can't be reversed without the salt. | Deleted automatically |
```

- Append a new section at the end of the file, before "Open questions":

```
## 10. Audit trail

Every change an admin or customer makes to products, categories, coupons, orders and users, and every sign-in
attempt, password reset and consent decision, is recorded in the append-only `audit_log` collection (ADR-019).
Admins read it at **Admin → Audit log**. The log never stores personal values: personal fields (name, phone,
and so on) are recorded as "changed" only, emails are never stored, and the IP is a salted hash. Because it
holds only opaque ids, erasing an account needs no change to the log. Reads of personal data are not logged.
The Admin Acceptable Use Agreement tells admins that their activity is recorded.
```

- [ ] **Step 3: Update `contracts.md`**

- In the `consents` row replace `purpose: terms\|privacy\|marketing_email` with `purpose: terms\|privacy\|marketing_email\|admin_aup`.
- Add this row to the data-model table after the `consents` row:

```
| `audit_log` (new, append-only) | `_id`, `at` (TTL index, `AUDIT_RETENTION`, default 365 d), `tenantId?` (reserved), `actorId?`, `actorRole: ADMIN\|USER\|ANONYMOUS\|SYSTEM`, `action` (see below), `entityType?`, `entityId?`, `outcome: SUCCESS\|FAILURE`, `changes[{field, before?, after?, redacted}]` (personal fields have `redacted: true` and no values), `subjectHash?` (salted hash of an attempted login email), `requestId?`, `ipHash`, `userAgent`. Never updated or deleted by the app |
```

- Add a new interface section after the Consent/Legal section (or at the end of the interfaces):

```
### Audit — `/admin/audit`
| Method & path | Auth | Request | Response |
|---|---|---|---|
| `GET /admin/audit` | 🛡 | `?entityType=&entityId=&actorId=&action=&outcome=success\|failure&from=&to=&page=` (`from`/`to` are ISO-8601 instants; 100 entries per page) | `{success, entries: [{_id, at, actorId?, actorRole, action, entityType?, entityId?, outcome, changes: [{field, before?, after?, redacted}], requestId?}], totalPage}`, newest first. 400 for an unknown `action`, `outcome` or malformed date. `ipHash`, `subjectHash` and `userAgent` are not returned |

`action` is one of: `PRODUCT_CREATED/UPDATED/DELETED`, `CATEGORY_CREATED/UPDATED/DELETED`, `COUPON_CREATED/UPDATED/DELETED`,
`ORDER_PLACED`, `ORDER_STATUS_CHANGED`, `ORDER_DELETED`, `USER_REGISTERED`, `USER_ROLE_CHANGED`, `USER_STATUS_CHANGED`,
`USER_ERASED`, `ACCOUNT_DELETED_SELF`, `PROFILE_UPDATED`, `ADDRESS_ADDED/UPDATED/DELETED`, `LOGIN_SUCCEEDED`,
`LOGIN_FAILED`, `LOGIN_BLOCKED_SUSPENDED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET_COMPLETED`, `CONSENT_CHANGED`.

Admin endpoints (every route a JWT with role ADMIN reaches, except `/user/me/**`, `/consent/**` and `/legal/**`)
return **403** until the admin has accepted the current acceptable-use agreement (`POST /consent` with
`purpose=admin_aup`).
```

- [ ] **Step 4: Add ADR-019 to `architecture.md`**

Insert this entry directly above the `- 2026-10-18: ADR-018` line:

```
- 2026-10-18: ADR-019 **Audit trail through an explicit `AuditTrail` port; redaction by allowlist; the admin
  agreement enforced on the server.** A new `audit` module owns the append-only `audit_log` collection (TTL
  retention, default 365 days). Services call `AuditTrail.record(AuditEvent)` after a successful write; the
  actor, IP hash and trace id are read from the request by `AuditService`, so callers cannot forge or forget
  them. Each entity has a `FieldPolicy`: only listed fields are logged, and personal fields are recorded as
  "changed" without values, so the log holds no personal data and erasure needs no log rewrite. A failed audit
  write is logged and counted (`sixpack.audit.write{result=failure}`) and never fails the action. The admin
  acceptable-use agreement is a new consent purpose (`admin_aup`, required for admins only) enforced by an
  interceptor that returns 403 on admin requests until it is accepted; the role comes from the JWT because
  `consent` cannot depend on `user`. Rejected: domain events (they would all need widening with actor and
  diff, and could lose the actor), persistence callbacks (no actor, and they miss the targeted `$set` updates
  of ADR-016), and a prompt-only agreement (bypassable through the API). Reads of personal data and a hash-chain
  for tamper evidence are out of scope; revisit if DPDP accountability or an audit requires them. Still active.
```

- [ ] **Step 5: Update `requirements.md` and `test-rollout.md`**

In `requirements.md` add, under the functional requirements (after FR11):

```
- [ ] FR12: Admins must accept an acceptable-use agreement before using admin endpoints, and again when its version changes.
- [ ] FR13: Data changes (products, categories, coupons, orders, users) and security events (logins, password resets, consent changes) are recorded in an append-only audit log that admins can search, without storing personal values.
```

In `test-rollout.md`, under "Rollout plan" step 5 (Data), append the sentence: `The \`audit_log\` collection and its indexes (including the retention TTL) are created at startup. Set \`LEGAL_ADMIN_AUP_VERSION\` and \`AUDIT_RETENTION\` in each environment if you want to change the defaults. Existing admins are asked to accept the agreement on their next page load.` Under "Rollback / monitoring" add: `- Alert on \`sixpack.audit.write{result="failure"}\` increasing: audit entries are being dropped. Changing \`AUDIT_RETENTION\` on a database that already has the \`at_1\` TTL index needs that index dropped first (\`db.audit_log.dropIndex("at_1")\`), because MongoDB refuses to change an index's options in place.`

(If Task 4 Step 11 fell back to the constant `365d`, replace the `AUDIT_RETENTION` sentences with: "Audit retention is fixed at 365 days in `AuditDocument`.")

- [ ] **Step 6: Align the privacy policy and the spec**

In `policies.ts`, add to the Privacy Policy's retention or security section (find the section that lists how long data is kept) one line: `"Audit log: a record of changes to the store's data and of sign-in attempts is kept for 1 year. It holds ids and the names of the fields that changed, not your personal details."` If the Privacy Policy has no retention section, add it as a new bullet in the closest section about how long data is kept.

In `docs/superpowers/specs/2026-10-18-admin-agreement-and-audit-design.md` make these edits:
- §2 table: change ``POLICIES.adminAup`` to ``POLICIES["admin-aup"]``.
- §3.1 and §3.2: change the helper location text so hashing moves to `shared.security.EvidenceHasher` (not `IpHasher`), and change the retention property to `app.audit.retention` (env `AUDIT_RETENTION`, default `365d`).
- §3.6: change "same page size style as products" to "100 entries per page".
- §5 Testing: replace the **Frontend** bullet with "**Frontend:** no test runner exists in the repo, so verify with `npm run build`, `npm run lint` and a manual browser check (admin sees the dialog on first login; a customer doesn't; the audit page lists and filters)."
- Set the status line to "Status: approved; implemented by `docs/superpowers/plans/2026-10-18-admin-agreement-and-audit.md`."

- [ ] **Step 7: Run the full automated gate**

Run: `cd backend && mvn -q verify`
Expected: BUILD SUCCESS (all tests, including `ModularityTests`, pass).

Run: `cd ecommerce-frontend && npm run build && npm run lint`
Expected: both exit 0.

- [ ] **Step 8: Manual browser check**

Use the `run` skill (or `docker-compose up`) to start the app with a real MongoDB, with `ADMIN_EMAIL` and a 12+ character `ADMIN_PASSWORD` set, then in a browser:

1. Sign in as the admin. Expected: the blocking dialog lists Terms, Privacy and "Admin Acceptable Use Agreement"; Escape does not close it; "Sign out" works.
2. Without accepting, open `/admin/analytics`. Expected: the admin pages show an error toast or empty data (the API returns 403), and no admin data loads.
3. Tick the box and accept. Expected: the dialog closes and `/admin/analytics` loads.
4. Open `/legal/admin-aup`. Expected: the agreement page renders; the customer footer does not link to it.
5. Create a product, edit its price, then open **Audit log**. Expected: `PRODUCT_CREATED` and `PRODUCT_UPDATED` rows with your admin id and `price: 400 → 450`; your own `LOGIN_SUCCEEDED` and `CONSENT_CHANGED` rows; filters (record type, action, result, dates) narrow the list and reset to page 1.
6. Sign out, try a wrong password. Sign back in as admin. Expected: a `LOGIN_FAILED` row with no email visible anywhere on the page.
7. Register a customer and open `/admin/audit` as that customer. Expected: redirected away from the admin area.
8. Check the date inputs and the Newer/Older buttons look acceptable in the admin toolbar at desktop and phone width. If they look unstyled, add minimal rules in `ecommerce-frontend/src/styles/admin-styles/_admin-ui.scss` next to the existing toolbar `select` rules.

Record any visual defects found and fix them before committing.

- [ ] **Step 9: Commit**

```bash
git add docs ecommerce-frontend backend .env.example k8s
git commit -m "docs: document the admin agreement, audit trail and ADR-019" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage**
- §2 admin agreement: purpose, version, role-aware `state`/`decide`, interceptor with exemptions, policy text and page, frontend mapping, validation pattern — Tasks 1, 2.
- §3.1 module layout — Tasks 3, 4, 5. §3.2 entry shape, `tenantId` reserved, IP hash via a shared helper, request id, indexes and TTL — Task 4. §3.3 every action is written from the listed services — Tasks 6 (catalogue, coupons, orders) and 7 (users, auth, consent); `ORDER_PLACED` in `CheckoutService`, `ACCOUNT_DELETED_SELF` in `AccountService`.
- §3.4 redaction and erasure — `Changes`/`FieldPolicy` (Task 3), `UserAudit` (Task 7), `EvidenceHasher` and the global-log assertions (Tasks 4, 7); append-only is enforced by a reflection test (Task 4).
- §3.5 failure behaviour — Task 4 (`aFailingWriteNeverReachesTheCallerAndIsCounted`).
- §3.6 read API and admin page — Tasks 5, 8; `SecurityConfig` rule in Task 5.
- §4 error table — Tasks 1 (403/400), 4 (write failure), 5 (bad filters, empty list).
- §5 testing — unit (`ChangesTest`, `EvidenceHasherTest`), integration per area, `ModularityTests` in Tasks 4, 6, 7, 9; frontend verified by build/lint/manual (spec corrected in Task 9).
- §6 documentation — Task 9. §7 tenancy readiness — `tenantId` field in Task 4. §8 defaults — 365d (Task 4), draft AUP wording (Task 2), stock edits only on admin edits (Task 6 audits admin writes only).

**Placeholder scan:** none; the only conditional is Task 4 Step 11's concrete fallback if the TTL placeholder isn't resolved, with the exact replacement and the doc change it implies.

**Type consistency:** `AuditTrail.record(AuditEvent)`, `AuditEvent.of/on/changing/failed/aboutSubject/by`, `Changes.policy/diff`, `FieldChange.of/redacted`, `AuditAction.parse`, `AuditRepository.append/search`, `AuditQuery(entityType, entityId, actorId, action, outcome, from, to, page, size)`, `EvidenceHasher.hash`, `ConsentService.state(userId, admin)` and `decide(userId, admin, purpose, granted, source, evidence)`, and the test helpers `adminToken/adminTokenWithoutAgreement/auditEntries/changeOf/placeOrder` are used with the same signatures across tasks. `ConsentService`'s constructor gains `EvidenceHasher` in Task 4 and `AuditTrail` in Task 7; Task 7 shows the full final constructor.

**Review Focus coverage:** 1 — Tasks 4 and 7 assert on `wholeLog()` / `toJson()`; 2 — Task 4; 3 — Task 1 (`adminEndpointsAreRefusedUntil…`, `customersAreNeverAsked…`); 4 — Task 5 (`badFiltersAreRejectedNotCrashed`, `pagesInHundreds`); 5 — Task 5 (`onlyAdminsCanReadTheLog`).
