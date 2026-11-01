# Marketing integrations: handoff

Status as of 2026-11-01. Read this first if you are picking the work up in a new session, account or tool.
Update the "Status" and "Pending" sections whenever something changes.

## Status at a glance

| | |
|---|---|
| Goal | Per-store (tenant-bound) Google and Meta marketing: consent banner, tags, product feed, server-side conversions, later one-click connect and ad reporting |
| Branch | `feat/marketing`, worktree `../6Pack-marketing` (sibling of the main `6Pack` checkout) |
| Built | Phases 0–3 of 6 |
| Tests | 756 backend, 189 frontend, lint and build green on the merged tree |
| Security scan | gitleaks, semgrep, trivy: nothing new from this branch |
| In `dev` | Yes, merged by pull request on 2026-11-01, before the browser check and owner decisions below |
| Next | Owner decisions → phases 4–5 once Google/Meta approve |

The main checkout (`6Pack`, branch `chore/central-secrets`) holds the owner's own uncommitted audit-log
changes. Do not touch, stash or commit them.

## Documents

| What | Where |
|---|---|
| System design (options, tenant binding, data, flows, API, security, ops, open questions) | `docs/superpowers/specs/2026-10-30-marketing-integrations-design.md` |
| Phase overview plan (0–5) | `docs/superpowers/plans/2026-10-30-marketing-integrations.md` |
| Detailed plans | `docs/superpowers/plans/2026-10-30-marketing-phase1-{backend,frontend}.md`, `2026-10-31-marketing-phase2-feed.md`, `2026-10-31-marketing-phase3-server-events.md` |
| Decisions | ADR-028 (in-house per-store module) and ADR-029 (secrets encrypted in Mongo) in `docs/foundations/architecture.md` |
| API contract | `docs/foundations/contracts.md` (marketing section) |
| Audit events | FR13 in `docs/foundations/requirements.md` |
| Consent and privacy | `docs/foundations/compliance-consent.md` |
| Architecture diagram | `.archify/architecture-marketing-integrations-20261030-221254/marketing-integrations.html` |

## What is built

Everything is off per store until a platform admin turns on the `marketing` feature for that store
(`Feature.MARKETING`; feature off → the admin endpoints return 404). Store `ADMIN` users configure it at
`/admin/app/marketing`.

| Phase | What it does | Main code |
|---|---|---|
| 0 Consent | Cookie banner with functional / analytics / marketing categories, Google Consent Mode v2. Choice kept in `localStorage` only (server sync deferred) | `ecommerce-frontend/src/components/CookieBanner.tsx`, `src/utils/consent.ts` |
| 1 Tags | Store owner pastes GA4, Google Ads (ID + label), GTM and Meta Pixel IDs; strict regex on both sides; shipped to the storefront in `/api/v1/tenant/config` (`marketing.tags`) and loaded only after consent | backend `com.sixpack.marketing` (`IntegrationService`, `MarketingAdminController`), `shared/tenant/MarketingTags` port; frontend `src/utils/tracking.ts`, `src/pages/admin/apps/marketing.tsx`, `src/redux/api/marketingAPI.ts` |
| 2 Product feed | One Google RSS 2.0 feed for Merchant Center and Meta at `GET /feeds/{token}/products.xml`; secret per-store token (rotatable), ETag/304, 15-minute cache, per-IP rate limit | `CatalogFeedService`, `FeedSettingsService`, `FeedController`, `FeedWriter`; frontend `FeedCard.tsx`, `feedAPI.ts` |
| 3 Server conversions | Paid orders sent to Meta Conversions API and GA4 Measurement Protocol from the server; secrets AES-256-GCM encrypted (`MARKETING_KEYS`); outbox with lease + fencing, retries with backoff, per-provider breaker; test button and delivery log in the admin | `ServerEventsService`, `ConversionDispatcher`, `Conversions`, `MongoOutbox`, `infra/meta`, `infra/google`, `infra/http/ProviderHttp`; frontend `ServerEventsSection.tsx`, `src/utils/attribution.ts` |

Tenant binding rules (spec §4.3): every collection carries `tenantId` through `TenantMongo`; the dispatcher
sets the store from the outbox row, never from ambient context; store export skips secrets, the feed token
and the outbox; store purge removes them.

Config (`backend/src/main/resources/application.yml`, `app.marketing.*`):

- `MARKETING_KEYS`: `k1:<base64 of 32 random bytes>`, newest first, older keys after a comma for rotation.
  Empty means server-side conversions fail closed; tags and feed still work. Generate one with
  `openssl rand -base64 32`. Never commit a real value.
- `pii-consent-version: "2026-10-31"`: hashed email/phone go to Meta only for shoppers whose cookie consent
  was given under this notice version or later.
- `calling-code: "91"`: India only for now.

## Pending

### Still open (merged into `dev` without them)

- [x] **Browser check, 2026-11-01** (local docker stack, Chromium). Passed: cookie banner (customise, saved
      choice survives reload, footer reopens it); Consent Mode default-denied, GA4 only with analytics consent,
      Ads and Meta Pixel only with marketing consent; tag form validation in the page and in the API (400 on a
      bad pixel ID); feed on, XML with 27 items, ETag/304, wrong token 404, rotation asks first and kills the
      old URL; audit records for every change with the feed token redacted. Found and fixed on
      `fix/marketing-browser-check`: cards overlapping on short windows, and the CSP blocking
      `ad.doubleclick.net` for Google Ads.
- [x] **Server events and a real purchase, 2026-11-01** (fake secrets, fake payment gateway). Secrets saved
      and never returned by the API, audit or store export; test buttons give honest results (GA4 does not
      check the secret, Meta rejected it); a gclid/fbclid visit with full consent placed an order: browser
      `purchase` event with the order id, GA4 server event SENT on the first try, Meta FAILED once with
      `AUTH_FAILED` (not retried), both shown in the delivery log.
- [ ] **Checkout lands on the empty cart after paying** (`pages/shipping.tsx`: the "cart is empty → /cart"
      effect races the navigation to `/order/:id`). Not marketing code; the order itself is placed.
- [ ] **Owner decision: privacy version.** Phase 3 sends hashed email and phone to Meta. Decide whether to raise
      `LEGAL_PRIVACY_VERSION` (currently defaults to `2026-10-16`), which re-prompts existing users. The
      privacy policy (P2) and cookie notice (P6) text must mention advertising partners, see
      `compliance-consent.md`.
- [ ] **Owner decision: open questions Q1–Q6** (spec §10). They were filled with defaults, not answered:
      Google + Meta first; store `ADMIN` connects; paste-IDs before OAuth; platform registers its own
      Google/Meta apps; feature switch, no billing; India (DPDP) only.
- [x] Push `feat/marketing` and `dev` (done 2026-11-01).
- [x] Pull request into `dev` merged (2026-11-01).

### Not started

- **Phase 4, one-click connect (OAuth) for Google and Meta.** Blocked until Google and Meta approve the
  platform's apps (company verification). Only the owner can apply.
- **Phase 5, ad spend and return-on-ad-spend reporting** (`marketing_daily`). Depends on phase 4.

### Known limits

- The platform admin turns Marketing on by editing the store's JSON (`"features": {"marketing": true}`) in
  `/platform` → Edit; there is no toggle yet.
- The feed URL shown in the admin is `https://<store domain>/…`, so on a local stack it lacks the `:8081` port.

- CI's "Build, scan & push images" job fails on every branch: the repo has no registry login secrets for
  `docker/login-action`. Tests, lint, build and k8s validation pass.

- The Content Security Policy (`ecommerce-frontend/nginx.conf.template`) blocks custom HTML tags inside
  Google Tag Manager; GTM's built-in tag types work.
- Cookie choice is browser-only; it is not yet synced to the user's server-side consents.

### Separate from marketing

- 11 HIGH/CRITICAL dependency CVEs (Tomcat, Spring MVC, Jackson in `backend/pom.xml`) already exist in `dev`;
  they need their own `fix/` branch from `dev`.

## How to verify

    cd ../6Pack-marketing/backend && mvn -q verify
    cd ../6Pack-marketing/ecommerce-frontend && npm ci && npm test && npm run lint && npm run build

Secret scan of only this branch's commits, run from the main checkout:

    docker run --rm -v "$PWD":/repo:ro -v "$PWD/../6Pack-marketing/.gitleaksignore":/ignore:ro \
      ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --log-opts="dev..feat/marketing" --gitleaks-ignore-path /ignore

## Working notes (things that cost time before)

- **gitleaks false positive.** The rule `generic-api-key` matches the GA4 measurement-ID form field's name.
  Known matches are fingerprinted in `.gitleaksignore`. Do not write that field name in comments or docs; each
  new occurrence is a new finding.
- **Worktrees** live next to the main checkout (`../6Pack-*`). Tools that sandbox writes to the current folder
  need permission to run git, mvn or npm there.
- **Subagents** need an explicit model; the default subagent model setting on this machine is invalid.
- **Local backend runs**: set both `MONGO_URI` and `MONGO_DB` to a throwaway database, or startup bootstraps
  write into the shared docker stack's `Ecommerce_2024` database.
- **Store-level defaults** must be per-store settings (`StoreSettings` / `StoreConfig`), with platform `app.*`
  values only as the fallback.
- **Branches**: never commit on `main` or `dev`; sub-features go on `feat/marketing--<slug>` and merge back
  into `feat/marketing` with `--no-ff`. Ask the owner before push, merge into `dev`, or deleting branches.
- **Audit**: new admin actions get an `AuditAction` value, a test asserting the record, and an entry in the
  frontend `AUDIT_ACTIONS` list. Secrets and tokens never go into audit records.
