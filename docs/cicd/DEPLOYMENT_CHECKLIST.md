# Deployment checklist

Use this for the first deploy to a cluster, and again when a release adds configuration. Tick each box in your
own copy of the list; the right-hand notes say where to look.

## 1. Repository

- [ ] GitHub secrets `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `KUBE_CONFIG` exist (`gh secret list`). See [README.md](README.md).
- [ ] Docker Hub repositories `sixpack-backend` and `sixpack-frontend` exist and the token can push to them.
- [ ] GitHub Environments `test` and `prod` exist; `prod` requires a reviewer.

## 2. Cluster (once)

- [ ] Ingress controller in `ingress-nginx`; cert-manager with a `letsencrypt-prod` ClusterIssuer.
- [ ] `infra/observability/k8s/install.sh` has run. Deploys fail until it does, because the overlays create a `ServiceMonitor`.
- [ ] Namespaces `test-env` and `prod-env` exist.
- [ ] A CNI that enforces NetworkPolicies (Calico, Cilium, GKE Dataplane V2, EKS network policy agent).
- [ ] The Sealed Secrets key is backed up somewhere that is not git (`k8s/README.md`).

## 3. Per-environment secrets (sealed)

Seal with `k8s/scripts/seal-backend-secrets.sh <test|prod> <env file>`. The script refuses to run unless all of
these are present.

- [ ] `JWT_SECRET` — at least 32 bytes (`openssl rand -base64 48`). Changing it signs everyone out.
- [ ] `MONGO_URI`
- [ ] `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` — `rzp_test_…` for test, `rzp_live_…` for prod ([guide](../RAZORPAY_INTEGRATION.md)).
- [ ] `ADMIN_EMAIL`, `ADMIN_PASSWORD` — creates the first admin if missing; 12+ characters.
- [ ] `CONSENT_IP_SALT` — `openssl rand -base64 32`. Set it once and never change it: it salts the stored IP and
      login-email hashes. If it is blank the backend logs a warning and the hashes are unsalted.
- [ ] The sealed file is added to the overlay's `resources:` and committed.

## 4. Per-environment configuration (`k8s/overlays/<env>/kustomization.yaml`, `backend-config`)

- [ ] `FRONTEND_URL` and `CLIENT_URL` are the real shop URL, and the Ingress host/TLS patch matches it. Password-reset
      and email-verification links are built from `FRONTEND_URL`.
- [ ] `INVOICE_SELLER_NAME`, `_ADDRESS`, `_GSTIN`, `_STATE`, `_EMAIL`, `_PHONE` for prod. `INVOICE_SELLER_STATE`
      must match how customers spell the state ("Karnataka"), or every invoice shows IGST.
- [ ] `LEGAL_TERMS_VERSION` / `LEGAL_PRIVACY_VERSION` only if the policy text changed materially. Bumping re-prompts every user.
- [ ] `SPRINGDOC_ENABLED` is unset or `false` in prod.
- [ ] `PAYMENT_PROVIDER=razorpay` in test and prod. The `fake` gateway refuses to start under the `prod` profile.

## 5. Mail

The backend has no mail provider yet: `LoggingMailSender` only logs links, and in prod (`mail-log-links: false`) it
logs a warning and sends nothing. Until a real `MailSender` adapter is added:

- [ ] Customers cannot receive password-reset or email-verification emails in prod. Decide whether that blocks launch.

## 6. Before merging to `main`

- [ ] Read [`foundations/test-rollout.md`](../foundations/test-rollout.md): data changes in this release and the rollback notes.
- [ ] The branch's deploy to `test-env` finished green.
- [ ] Smoke test on test: register, verify email (link in the backend log), browse, checkout with Razorpay test keys,
      refund from the admin order page, open the invoice, admin dashboard.

## 7. After the prod deploy

- [ ] `kubectl -n prod-env rollout status deploy/backend` and `deploy/frontend` are complete.
- [ ] `/actuator/health/readiness` is UP behind the ingress, and Grafana shows traffic.
- [ ] Place one real low-value order, then refund it.
- [ ] Alerts are routed to a person (`infra/observability/k8s/values/alertmanager-slack.yaml`).
