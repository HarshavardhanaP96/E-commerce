# 6Pack E-Commerce

A single-store e-commerce app. Customers browse, search, check out with Razorpay, and track their orders.
Admins manage products, customers, orders and coupons, and see analytics.

| Part | Stack |
|---|---|
| `backend/` | Java 21, Spring Boot 3.5, Spring Modulith (modular monolith, hexagonal per module), MongoDB, Spring Security (JWT), Caffeine, Micrometer + OpenTelemetry |
| `ecommerce-frontend/` | React 18, TypeScript, Vite, Redux Toolkit / RTK Query, SCSS, Chart.js, served by unprivileged nginx |
| `k8s/` | Kustomize base + `test` / `prod` overlays |
| `infra/observability/` | Local Prometheus/Grafana/Jaeger, plus the cluster stack (kube-prometheus-stack, Loki, Tempo, OTel collectors, Sealed Secrets) — see its [README](infra/observability/README.md) |

The standing project docs live in [`docs/foundations/`](docs/foundations/): vision, requirements,
architecture and decision log, API contracts, and test/rollout. The October 2026 security and architecture
audit is in [`docs/AUDIT.md`](docs/AUDIT.md).

## Quick start (Docker)

```bash
cp .env.example .env            # then set JWT_SECRET (openssl rand -base64 48) and ADMIN_PASSWORD
docker compose up --build       # app: http://localhost:8081   API: http://localhost:8080
```

Use `BACKEND_PORT=… FRONTEND_PORT=…` if those ports are taken. The bootstrap admin is created from
`ADMIN_EMAIL` / `ADMIN_PASSWORD`. Payments use the **fake** gateway unless you set `PAYMENT_PROVIDER=razorpay`
together with your keys.

Metrics and traces:

```bash
TRACING_EXPORT_ENABLED=true docker compose --profile observability up --build
# Grafana http://localhost:3001 (admin/admin) · Prometheus http://localhost:9090 · Jaeger http://localhost:16686
```

## Local development

```bash
docker compose up -d mongo
cd backend && JWT_SECRET=$(openssl rand -base64 48) mvn spring-boot:run       # :8080
cd ecommerce-frontend && npm ci && npm run dev                                 # :5173, proxies /api to :8080
```

API docs: http://localhost:8080/swagger-ui.html (turned off in prod).

## Tests

```bash
cd backend && mvn verify          # unit + Testcontainers (needs Docker) + module-boundary check
cd ecommerce-frontend && npm run lint && npm run build
```

## Backend modules

`user` · `product` · `coupon` · `payment` · `storage` · `order` · `dashboard` · `shared`. Each module exposes
a small public API in its root package. Its `web` / `application` / `domain` / `infra` sub-packages are
private, and `ModularityTests` enforces this. Swapping MongoDB, local disk or Razorpay means writing a new
`infra` adapter, and any module can later be extracted into its own service.

## Deployment

CI (`.github/workflows/main.yml`) runs tests, then builds and scans the images (Trivy), pushes them to Docker
Hub, and deploys with kustomize. Non-main branches go to `test-env`; `main` goes to `prod-env`, with a
readiness-gated rollout and automatic rollback. See [`k8s/README.md`](k8s/README.md) for one-time
cluster setup (observability stack, sealed secrets, ingress, TLS).

Required GitHub secrets: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `KUBE_CONFIG`.
