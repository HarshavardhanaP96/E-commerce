#!/usr/bin/env bash
# Installs / upgrades the cluster-wide platform pieces. Idempotent: re-run after changing any values file.
#   observability ns: kube-prometheus-stack (Prometheus, Alertmanager, Grafana), Loki, Tempo,
#                     otel-collector (trace gateway), otel-logs (log agent)
#   kube-system:      sealed-secrets controller
# Then applies the 6Pack alert rules + Grafana dashboard (infra/observability/kustomization.yaml).
#
# Usage:  infra/observability/k8s/install.sh
# Env:    SLACK_WEBHOOK_URL   optional; route warning/critical alerts to Slack
#         GRAFANA_ADMIN_PASSWORD  optional; only used the first time (otherwise a random one is generated)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VALUES="$HERE/values"
NS=observability

# Pinned chart versions. Bump deliberately and read the chart's upgrade notes first.
KPS_VERSION=92.7.0
LOKI_VERSION=7.3.0
TEMPO_VERSION=3.2.0
OTEL_VERSION=0.176.0
SEALED_SECRETS_VERSION=2.20.0

echo "→ cluster: $(kubectl config current-context)"

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null
helm repo add grafana-community https://grafana-community.github.io/helm-charts >/dev/null  # Tempo moved here
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts >/dev/null
helm repo add sealed-secrets https://bitnami.github.io/sealed-secrets >/dev/null
helm repo update >/dev/null

kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

# Grafana admin login: created once, never overwritten.
if ! kubectl -n "$NS" get secret grafana-admin >/dev/null 2>&1; then
  pw="${GRAFANA_ADMIN_PASSWORD:-$(openssl rand -base64 24)}"
  kubectl -n "$NS" create secret generic grafana-admin \
    --from-literal=admin-user=admin --from-literal=admin-password="$pw"
  [ -z "${GRAFANA_ADMIN_PASSWORD:-}" ] && echo "→ Grafana admin password (save it now): $pw"
fi

kps_extra=()
if [ -n "${SLACK_WEBHOOK_URL:-}" ]; then
  kubectl -n "$NS" create secret generic alertmanager-slack \
    --from-literal=webhook-url="$SLACK_WEBHOOK_URL" --dry-run=client -o yaml | kubectl apply -f -
  kps_extra=(-f "$VALUES/alertmanager-slack.yaml")
else
  echo "→ SLACK_WEBHOOK_URL not set: alerts will only show in the Alertmanager/Grafana UI"
fi

helm upgrade --install sealed-secrets sealed-secrets/sealed-secrets --version "$SEALED_SECRETS_VERSION" \
  -n kube-system -f "$VALUES/sealed-secrets.yaml" --wait

helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack --version "$KPS_VERSION" \
  -n "$NS" -f "$VALUES/kube-prometheus-stack.yaml" "${kps_extra[@]}" --wait --timeout 10m

helm upgrade --install loki grafana/loki --version "$LOKI_VERSION" \
  -n "$NS" -f "$VALUES/loki.yaml" --wait --timeout 10m

helm upgrade --install tempo grafana-community/tempo --version "$TEMPO_VERSION" \
  -n "$NS" -f "$VALUES/tempo.yaml" --wait

helm upgrade --install otel-collector open-telemetry/opentelemetry-collector --version "$OTEL_VERSION" \
  -n "$NS" -f "$VALUES/otel-collector.yaml" --wait

helm upgrade --install otel-logs open-telemetry/opentelemetry-collector --version "$OTEL_VERSION" \
  -n "$NS" -f "$VALUES/otel-logs.yaml" --wait

# Needs the PrometheusRule CRD from kube-prometheus-stack, so it goes last.
kubectl apply -k "$HERE/.."

cat <<MSG

Done. Open the UIs with port-forwards:
  kubectl -n $NS port-forward svc/kube-prometheus-stack-grafana 3000:80
  kubectl -n $NS port-forward svc/kube-prometheus-stack-prometheus 9090
  kubectl -n $NS port-forward svc/kube-prometheus-stack-alertmanager 9093
Next: seal the app secrets per environment (k8s/scripts/seal-backend-secrets.sh).
MSG
