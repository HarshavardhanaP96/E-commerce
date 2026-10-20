# Quick start

The shortest paths to run, test and ship. Details are in the linked pages.

## Run everything locally

```bash
cp .env.example .env     # set JWT_SECRET (openssl rand -base64 48) and ADMIN_PASSWORD (12+ characters)
docker compose up --build
```

- App: http://localhost:8081 · API: http://localhost:8080 (change with `FRONTEND_PORT` / `BACKEND_PORT`)
- Sign in with `ADMIN_EMAIL` / `ADMIN_PASSWORD`. The first admin visit asks you to accept the policies and the admin agreement.
- Payments use the **fake** gateway, so checkout works with no keys. Password-reset and email-verification links
  are written to the backend log (`MAIL_LOG_LINKS=true`).
- Metrics and traces: `TRACING_EXPORT_ENABLED=true docker compose --profile observability up --build`

For day-to-day development run only MongoDB in Docker and the apps on the host (see the root [`Readme.md`](../../Readme.md)).

## Test

```bash
cd backend && mvn verify                                   # unit + integration (Docker needed) + module boundaries
cd ecommerce-frontend && npm run lint && npm run build
```

## Ship

1. Push a branch. CI runs the checks, builds and scans the images, and deploys to `test-env`.
2. Open a PR to `main`. It runs the checks only.
3. Merge to `main`. CI deploys to `prod-env` and rolls back by itself if the rollout is not ready.

First time on a new cluster or repository? Work through the [deployment checklist](DEPLOYMENT_CHECKLIST.md).

## Where things are

- Pipeline: [`.github/workflows/main.yml`](../../.github/workflows/main.yml) — explained in [README.md](README.md)
- Cluster manifests and sealed secrets: [`k8s/`](../../k8s/README.md)
- Payments: [`../RAZORPAY_INTEGRATION.md`](../RAZORPAY_INTEGRATION.md) · API docs: [`../SWAGGER_API_DOCS.md`](../SWAGGER_API_DOCS.md)
