# Kubernetes

`base/` holds the environment-agnostic manifests. `overlays/test` and `overlays/prod` set the namespace,
config, host, and replica counts. CI renders an overlay with `kustomize edit set image` and applies it.

## One-time cluster setup (before the first deploy)

1. **Platform add-ons:** an nginx ingress controller (namespace `ingress-nginx`, which the NetworkPolicies
   allow), and cert-manager with a `letsencrypt-prod` ClusterIssuer (prod TLS).
2. **Observability + Sealed Secrets:** run [`infra/observability/k8s/install.sh`](../infra/observability/k8s/install.sh).
   It installs Prometheus/Alertmanager/Grafana, Loki, Tempo, the OTel trace collector and log agent, and the
   Sealed Secrets controller. The overlays include a `ServiceMonitor`, so **deploys fail until this has run**
   (the CRD doesn't exist yet). See [`infra/observability/README.md`](../infra/observability/README.md).
3. **App secrets, per environment** (`test`, then `prod`), with `kubectl` and `kubeseal` pointed at the cluster:

   ```bash
   kubectl create namespace prod-env
   cat > /tmp/prod.secret.env <<'ENV'
   JWT_SECRET=<openssl rand -base64 48>
   MONGO_URI=mongodb+srv://USER:PASS@cluster/Ecommerce_2024
   RAZORPAY_KEY_ID=rzp_live_xxx
   RAZORPAY_KEY_SECRET=xxx
   ADMIN_EMAIL=admin@yourdomain.com
   ADMIN_PASSWORD=a-long-unique-password
   CONSENT_IP_SALT=<openssl rand -base64 32>   # salts IPs kept as consent evidence; never change it
   # Multi-tenancy (optional until a second tenant exists):
   # PLATFORM_ADMIN_EMAIL=ops@yourdomain.com      # signs in at /platform; manages tenants, sees no store data
   # PLATFORM_ADMIN_PASSWORD=<12+ characters>
   # TENANT_SECRET_ACME=<that tenant's Razorpay key secret>   # one per tenant, named by its payment.secretRef
   ENV
   k8s/scripts/seal-backend-secrets.sh prod /tmp/prod.secret.env && rm /tmp/prod.secret.env
   ```

   This writes `k8s/overlays/prod/backend-secrets.sealed.yaml` (encrypted; safe to commit). Add it to that
   overlay's `resources:` and commit; CI then deploys the secret with the app. Only the controller in that
   cluster can decrypt it, so re-seal if the cluster (or its sealing key) is ever replaced. Back up the key:
   `kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml`
   (store it somewhere safe, never in git).

## What the overlays contain

- `base/`: Deployments, Services, PDBs, Ingress, and NetworkPolicies (ingress deny-by-default; frontend
  accepts only the ingress controller, backend only the frontend and Prometheus). Egress is open.
  NetworkPolicies need a CNI that enforces them (Calico, Cilium, GKE Dataplane V2, EKS network policy agent).
- `components/monitoring/`: the backend `ServiceMonitor`. Both overlays include it.
- `overlays/test`: 100% trace sampling, mail links logged. `overlays/prod`: 10% sampling, TLS, frontend HPA.

## Preview / apply by hand

```bash
kubectl kustomize k8s/overlays/test
kubectl apply -k k8s/overlays/test
kubectl -n test-env rollout status deploy/backend
```

Roll back with `kubectl -n prod-env rollout undo deploy/backend`.
