# Observability

The backend emits three signals in every environment: Prometheus metrics (`/actuator/prometheus`),
OTLP traces, and JSON (ECS) logs on stdout. What collects them depends on where it runs.

| | Local (`docker compose --profile observability`) | test-env / prod-env (k8s) |
|---|---|---|
| Metrics | Prometheus (`prometheus.yml`) | kube-prometheus-stack, via the `ServiceMonitor` in `k8s/components/monitoring` |
| Traces | Jaeger | backend → `otel-collector` → Tempo. 100% sampled in test, 10% in prod |
| Logs | `docker compose logs` | `otel-logs` DaemonSet → Loki (7 days) |
| Dashboards | Grafana, provisioned from `grafana/provisioning/` | Same dashboard JSON, loaded by Grafana's sidecar. Has a namespace picker |
| Alerts | — | `k8s/alerts.yaml` + the chart's built-in Kubernetes rules → Alertmanager → Slack (optional) |

Every request metric, business counter, request span and log line carries a `tenant` label with the store's
slug (`unknown` when the host matched no store, `none` on platform and operational paths). Use the dashboard's
Store picker, or `{tenant="acme"}` in PromQL and `| json | tenant="acme"` in Loki.

## Files

- `prometheus.yml`, `grafana/`: the local stack. `grafana/provisioning/dashboards/sixpack-overview.json` is
  also what the cluster Grafana shows, so edit the dashboard in one place.
- `kustomization.yaml`: the cluster-wide 6Pack objects (alert rules and the dashboard ConfigMap) in the
  `observability` namespace.
- `k8s/install.sh`: installs or upgrades everything below. Chart versions are pinned at the top of the script.
- `k8s/values/`: one Helm values file per chart.

| Release | Chart | Purpose |
|---|---|---|
| `kube-prometheus-stack` | prometheus-community/kube-prometheus-stack | Prometheus (15d / 20Gi), Alertmanager, Grafana, node-exporter, kube-state-metrics |
| `loki` | grafana/loki | Log storage, single binary on a 10Gi PVC |
| `tempo` | grafana-community/tempo | Trace storage, 72h on a 10Gi PVC |
| `otel-collector` | open-telemetry/opentelemetry-collector | Trace gateway at `otel-collector.observability:4318` |
| `otel-logs` | open-telemetry/opentelemetry-collector | DaemonSet that tails pod logs and ships them to Loki over OTLP |
| `sealed-secrets` | sealed-secrets/sealed-secrets | Decrypts committed SealedSecrets (`kube-system`) |

## Install

Run this once per cluster, and again after changing a values file, a pinned version, or `alerts.yaml`:

```bash
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/... infra/observability/k8s/install.sh
```

`SLACK_WEBHOOK_URL` is optional. Without it, alerts only appear in the Alertmanager and Grafana UIs. The first
run prints a generated Grafana admin password; it is stored in the `grafana-admin` Secret.

Open the UIs with `kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80`
(Prometheus: `svc/kube-prometheus-stack-prometheus 9090`, Alertmanager:
`svc/kube-prometheus-stack-alertmanager 9093`). To expose Grafana publicly, add an Ingress with TLS and
SSO in front of it rather than a port-forward.

## Alerts

`prod-env` alerts are `critical` and `test-env` alerts are `warning`, where the rule supports both.

| Alert | Fires when |
|---|---|
| SixpackBackendUnavailable | no ready backend pods for 2m |
| SixpackBackendScrapeDown | Prometheus can't scrape a backend pod for 5m |
| SixpackReadinessFlapping | a backend pod's readiness changes more than 4 times in 15m |
| SixpackHigh5xxRate | more than 2% of requests return 5xx for 5m |
| SixpackHighLatency | p95 above 500ms for 10m |
| SixpackPaidButUnfulfilled | a payment was captured but no order was created (any occurrence), per store |
| SixpackBadPaymentSignatures | more than 5 invalid Razorpay signatures in 15m for one store |
| SixpackBackendMemoryNearLimit | container above 90% of its memory limit for 10m |
| SixpackAuditWriteFailing | any audit log write failed in 10m, per store |

## Sizing and limits

These defaults suit one small cluster running both environments. Before relying on them under real load:

- Move Loki and Tempo to object storage (S3/GCS), and their distributed charts, when one pod is no longer enough.
- Grafana has no persistence on purpose, because everything is provisioned. Dashboards edited in the UI are
  lost on restart; export them into `grafana/provisioning/dashboards/` instead.
- On self-managed clusters, re-enable the `kubeEtcd`/`kubeScheduler`/`kubeControllerManager`/`kubeProxy`
  scrapes in `kube-prometheus-stack.yaml`.
