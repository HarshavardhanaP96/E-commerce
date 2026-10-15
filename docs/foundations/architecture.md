# Architecture & Decision Log

## Overview

```
Browser ──► nginx (ecommerce-frontend: static SPA + reverse proxy /api, /uploads)
                 │
                 ▼
          backend (Spring Boot 3.5, Java 21, modular monolith) ──► MongoDB (Ecommerce_2024)
                 │                                         └──► Razorpay REST API
                 └── /actuator/prometheus ◄── Prometheus ──► Grafana
                 └── OTLP traces ─────────► Jaeger (or Tempo/any OTLP collector)
```

### Backend module layout (`backend/src/main/java/com/sixpack/`)
Each module is a direct sub-package. Its **root package is its public API**: interfaces and records that
other modules may use. Everything in its sub-packages is internal, and Spring Modulith enforces this with
`ModularityTests` at build time.

| Module      | Public API (root package)                         | Internals |
|-------------|---------------------------------------------------|-----------|
| `shared`    | (open module) error model, security config, JWT, rate limit, web config | — |
| `user`      | `UserDirectory` (look up name / exists)            | `web` controllers + DTOs, `application` services, `domain` model + ports, `infra` Mongo + mail adapters |
| `product`   | `ProductCatalog` (snapshot, reserve and release stock) | same layering |
| `coupon`    | `CouponLookup` (discount for a code)              | same layering |
| `payment`   | `PaymentGateway` (create order, verify signature) | `infra.razorpay`, `infra.fake` adapters picked by `app.payment.provider` |
| `storage`   | `FileStorage` (store image, delete)               | `infra.local` disk adapter |
| `order`     | —                                                 | checkout + order lifecycle; uses product, coupon, payment and user APIs |
| `dashboard` | —                                                 | read-side Mongo aggregations (`DashboardReadPort`) |

Layer rules inside a module: `web → application → domain ← infra`. Domain types never import Spring Data
or Mongo classes. Each `infra` adapter implements a `domain` port. Moving to Postgres, S3 or a separate
service therefore means writing a new adapter, not rewriting the module.

### Environments
| Env   | Where | Config source | Payment | Notes |
|-------|-------|---------------|---------|-------|
| local | `docker compose up` or `mvn spring-boot:run` + `npm run dev` | `.env` (see `.env.example`) | `fake` | Mongo container, optional observability profile |
| test  | k8s namespace `test-env` (`k8s/overlays/test`) | ConfigMap + Secret `backend-secrets` | Razorpay test keys | 1 replica, deployed from every non-main branch push |
| prod  | k8s namespace `prod-env` (`k8s/overlays/prod`) | ConfigMap + Secret `backend-secrets` | Razorpay live keys | frontend 2–6 (HPA); backend 1 until ADR-008 is resolved; PDBs; deployed from `main` |

### DevOps pipeline (`.github/workflows/main.yml`)
`backend: mvn verify (Testcontainers)` ∥ `frontend: lint + build` → `docker build & push (sha tag)` →
`kustomize set image + kubectl apply -k` to test (branches) or prod (main), then `kubectl rollout status`.
A `manifests` job renders every overlay and Helm chart and validates them (kubeconform with CRD schemas,
promtool for alert rules) before images are built.
The rollout gate is the readiness probe. Rollback is `kubectl rollout undo` (see `test-rollout.md`).

### Observability plan
- **Metrics:** Micrometer → `/actuator/prometheus`. HTTP server metrics, JVM, Mongo driver pool, Caffeine
  cache, and business counters (`sixpack.orders.placed`, `sixpack.checkout.failed{reason}`,
  `sixpack.auth.login{result}`).
- **Traces:** Micrometer Tracing + OpenTelemetry bridge, OTLP HTTP export to `OTEL_EXPORTER_OTLP_ENDPOINT`.
  Sampling is 100% in local and test and 10% in prod (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`).
- **Logs:** Spring Boot structured logging (ECS JSON) to stdout, with `traceId`/`spanId` in MDC. In k8s an
  OTel collector DaemonSet (`otel-logs`) ships them to Loki. Grafana links trace ids in logs to Tempo.
- **Health:** liveness and readiness groups (readiness includes Mongo), used by k8s probes.
- **Local stack:** `docker compose --profile observability up` adds Prometheus, Grafana (Prometheus
  datasource provisioned) and Jaeger.
- **Cluster stack (ADR-012):** `infra/observability/k8s/install.sh` installs kube-prometheus-stack, Loki,
  Tempo and two OTel collectors into `observability`. The backend `ServiceMonitor` comes from
  `k8s/components/monitoring`.
- **Alerts** (`infra/observability/k8s/alerts.yaml`, through Alertmanager to Slack): 5xx rate > 2% for 5 min,
  p95 latency > 500 ms, readiness flapping, `checkout.failed{reason="paid_but_unfulfilled"}` > 0, no ready
  backend pods, scrape down, and memory near its limit. Severity is critical in prod-env and warning in test-env.

### Frontend UI (`ecommerce-frontend/src/`)
- **Design tokens** live in `styles/_tokens.scss` as CSS custom properties. There are two surfaces: the
  **store** (warm and editorial: coral accent, ink CTAs, Bricolage Grotesque headings) and the **admin**
  (neutral slate with an indigo accent, denser). `SurfaceScope` in `App.tsx` sets `<html data-surface>` from the
  route, so shared components pick up the right look. Components read tokens and do not hard-code colours.
  Chart.js can't read CSS variables, so `components/admin/chartTheme.ts` mirrors the admin palette.
- **Mobile store:** `components/bottomNav.tsx` is a tab bar shown below 640 px on all routes except `/admin` and
  `/shipping` (`utils/routes.ts#showsBottomNav`). The account menu (`components/accountMenu.tsx`) is shared
  between the header dropdown and the bottom sheet, which is a native `<dialog>` for focus trapping and Escape.
- **Admin conventions:** `DataTable` puts search, inline filters and the primary action (e.g. "Add product") in
  one toolbar. It uses fixed column widths with ellipsis, puts pagination and page size at the bottom, and
  scrolls the table body instead of the page. `DetailLayout` is the shell for product, user, order and
  new-product pages: it fits the viewport and puts Save / Update / Delete top-right.

## Key decisions (append-only log, newest first)
- 2026-10-15: ADR-013 **One token-driven design system with two surfaces (store and admin).** All styling is
  driven by CSS custom properties in `_tokens.scss`, scoped by `data-surface`. This replaced the old
  per-page SCSS (`_dashboard`, `_products`, `_chart`, `_mediaquery`, …) and the compile-time SCSS variables
  in `_mixin.scss`, which couldn't switch per surface at runtime. Fonts are Inter
  for UI and Bricolage Grotesque for store headlines. Text colours are checked for WCAG AA contrast, and
  `prefers-reduced-motion` is respected. The mobile store uses a bottom tab bar instead of header icons.
  Rejected: a component library such as MUI or Chakra (bundle weight, and harder to give the store its own
  look) and Tailwind (a large rewrite of existing SCSS). Revisit if a dark mode is needed: add a third token
  set and leave components unchanged. Still active.
- 2026-10-15: ADR-012 **Self-hosted observability with the standard Helm charts, plus Sealed Secrets.** The
  stack is kube-prometheus-stack (Prometheus, Alertmanager, Grafana), Loki and Tempo on filesystem PVCs, and
  OpenTelemetry collectors for traces (a gateway) and logs (a DaemonSet). Chart versions are pinned in
  `install.sh`. It runs once per cluster and is shared by test-env and prod-env, which are told apart by the
  `namespace` label. App secrets are committed as SealedSecrets instead of being created by hand. Ingress
  NetworkPolicies are deny-by-default; egress is open. Rejected: plain manifests (no alerting, not
  production-grade) and a hosted vendor (cost, plus another account). Revisit by moving Loki and Tempo to
  object storage when volume grows. Still active.
- 2026-10-14: ADR-011 **The SPA's nginx forwards `Host`/`X-Forwarded-Host` with the port, plus `X-Forwarded-Proto`
  from the ingress.** Spring (`forward-headers-strategy: framework`) then recognises same-origin requests.
  Without this, browser POSTs were rejected as cross-origin (403). `X-Forwarded-For` is replaced with one
  client IP (taken from the ingress's `X-Real-IP`) so the login rate limiter can't be bypassed. Still active.
- 2026-10-14: ADR-010 **Frontend calls the API same-origin via an nginx reverse proxy** (`/api`, `/uploads`).
  This removes the build-time `VITE_SERVER` coupling, so one image works in every environment, and CORS
  isn't needed in k8s. `VITE_SERVER` stays as an optional override for `npm run dev`. Still active.
- 2026-10-14: ADR-009 **Payment intents.** `POST /order/checkout` prices the cart on the server and stores a
  `payment_intents` record (user, items, amounts, Razorpay order id). `POST /order/new` accepts only the
  Razorpay ids and signature, verifies them, and builds the order from the stored intent. This stops price
  tampering and replay. Still active.
- 2026-10-14: ADR-008 **Uploads stay on local disk behind a `FileStorage` port.** In k8s this needs a
  ReadWriteMany PVC, or a single replica. An S3-compatible adapter is the next step before scaling beyond
  one node. Still active.
- 2026-10-14: ADR-007 **Caffeine in-process cache with short TTLs (60 s catalogue, 120 s dashboard)** in
  place of NodeCache, with explicit evictions on writes. With several replicas, a pod can serve stale data
  for up to one TTL. Moving to Redis is a config change (`spring.cache.type=redis`) once Redis exists.
  Still active.
- 2026-10-14: ADR-006 **JWT via Spring Security OAuth2 resource server (HS256, Nimbus).** Tokens carry
  `sub=userId` and `role`, and expire after 24 h. `JWT_SECRET` must be ≥ 32 bytes or the app won't start.
  This replaces the 30-day tokens that fell back to the secret `"secret"`. Still active.
- 2026-10-14: ADR-005 **Raw Razorpay REST via `RestClient` instead of the Razorpay Java SDK.** Only two
  calls are needed (create order, plus HMAC verify done locally), so the SDK dependency isn't worth it.
  Still active.
- 2026-10-14: ADR-004 **Keep the `/api/v1` paths and JSON field names, including `_id`,** so the frontend
  changes stay small. Fixes: `totalPage` (was `totalpage`), consistent chart keys, and responses that
  never include the password. Still active.
- 2026-10-14: ADR-003 **Spring Boot 3.5.16 + Spring Modulith 1.4.13** instead of Boot 4.x. 3.5 is the
  mature, well-documented line. Revisit Boot 4 (Jackson 3, modular starters) once this migration is stable.
  Still active.
- 2026-10-14: ADR-002 **Modular monolith, hexagonal per module** (see the table above). It fits the app's
  size, and the module boundaries are where it would split into services later. Still active.
- 2026-10-14: ADR-001 **Keep MongoDB** (Spring Data MongoDB), with the same database and collection
  names (`users`, `products`, `orders`, `coupons`). There's no data migration, and bcrypt `$2b$` hashes
  verify with Spring's `BCryptPasswordEncoder`. Postgres stays possible by swapping adapters. Still active.
