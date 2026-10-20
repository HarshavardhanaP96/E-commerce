# CI/CD

The pipeline is one GitHub Actions workflow, [`.github/workflows/main.yml`](../../.github/workflows/main.yml).
This page explains what it does and how to operate it. One-time cluster setup lives in
[`k8s/README.md`](../../k8s/README.md); the observability stack in
[`infra/observability/README.md`](../../infra/observability/README.md).

## What runs

| Job | Runs on | What it does |
|---|---|---|
| `backend` | every push and PR | `mvn -B verify` in `backend/` with Java 21: unit tests, Testcontainers integration tests (MongoDB 7), and the Spring Modulith boundary check. Uploads the Surefire reports if it fails. |
| `frontend` | every push and PR | `npm ci`, `npm run lint`, `npm run build`, then `npm audit --omit=dev --audit-level=high` in `ecommerce-frontend/` (Node 20). |
| `manifests` | every push and PR | Renders the `test` and `prod` kustomize overlays, the observability kustomization and all Helm charts, validates them with kubeconform (CRDs included) and checks the alert rules with `promtool`. |
| `images` | pushes only, after the three jobs above pass | Builds both Docker images, scans them with Trivy (fails on fixable CRITICAL findings), then pushes `:<commit sha>`, and `:latest` from `main`. |
| `deploy` | pushes only, after `images` | Points the overlay at this commit's images, applies it with `kubectl`, waits for the rollouts, and rolls back if that fails. |

Concurrency: a newer push to the same ref cancels an older CI run, but deploys to one environment are queued,
never cancelled halfway.

## Which branch goes where

| Push to | Environment | Namespace | Kustomize overlay |
|---|---|---|---|
| `main` | `prod` | `prod-env` | `k8s/overlays/prod` |
| any other branch | `test` | `test-env` | `k8s/overlays/test` |

Pull requests run the checks but never build images or deploy. Use GitHub's **Environments** settings to require a
reviewer before the `prod` deploy and to keep prod-only secrets out of `test`.

## Repository secrets

| Secret | Value |
|---|---|
| `DOCKERHUB_USERNAME` | Docker Hub account that owns `sixpack-backend` and `sixpack-frontend` |
| `DOCKERHUB_TOKEN` | A Docker Hub access token with write access to those repositories |
| `KUBE_CONFIG` | The raw kubeconfig YAML for the cluster (the workflow writes it as-is, so don't base64 it) |

Application secrets (`JWT_SECRET`, `MONGO_URI`, Razorpay keys, `ADMIN_*`, `CONSENT_IP_SALT`) are **not** GitHub
secrets. They are committed as Sealed Secrets and deployed with the overlay; see `k8s/README.md`.

## Roll back

A failed rollout is undone automatically. To undo a bad release that passed readiness:

```bash
kubectl -n prod-env rollout undo deploy/backend
kubectl -n prod-env rollout undo deploy/frontend
```

Check [`foundations/test-rollout.md`](../foundations/test-rollout.md) first: some releases add data that an
older image can't read (the admin agreement rows are the current example).

## Run the same checks locally

```bash
cd backend && mvn verify                                   # needs Docker
cd ecommerce-frontend && npm ci && npm run lint && npm run build
kubectl kustomize k8s/overlays/prod > /dev/null            # does the overlay render?
```

## Dependency updates

[`.github/dependabot.yml`](../../.github/dependabot.yml) opens PRs weekly for Maven (Spring packages grouped) and npm,
and monthly for the Docker base images and GitHub Actions. They go through the same checks; merge them like any
other change.
