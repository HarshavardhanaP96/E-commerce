# Kubernetes

`base/` holds the environment-agnostic manifests. `overlays/test` and `overlays/prod` set the namespace,
config, host, and replica counts. CI renders an overlay with `kustomize edit set image` and applies it.

## One-time setup per environment

```bash
kubectl create namespace prod-env
kubectl -n prod-env create secret generic backend-secrets \
  --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
  --from-literal=MONGO_URI='mongodb+srv://USER:PASS@cluster/Ecommerce_2024' \
  --from-literal=RAZORPAY_KEY_ID='rzp_live_xxx' \
  --from-literal=RAZORPAY_KEY_SECRET='xxx' \
  --from-literal=ADMIN_EMAIL='admin@yourdomain.com' \
  --from-literal=ADMIN_PASSWORD='a-long-unique-password'
```

Prefer an external secret manager (External Secrets Operator, Sealed Secrets, or your cloud's CSI driver)
over hand-made secrets once that infrastructure exists.

Requirements: an nginx ingress controller, and cert-manager with a `letsencrypt-prod` ClusterIssuer (prod TLS).
An OTLP collector at `otel-collector.observability:4318` is optional. Without one, set
`TRACING_EXPORT_ENABLED=false`. Prometheus scrapes pods through the `prometheus.io/*` annotations; if you
run the Prometheus Operator, add a `ServiceMonitor` instead.

## Preview / apply by hand

```bash
kubectl kustomize k8s/overlays/test
kubectl apply -k k8s/overlays/test
kubectl -n test-env rollout status deploy/backend
```

Roll back with `kubectl -n prod-env rollout undo deploy/backend`.
