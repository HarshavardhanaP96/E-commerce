#!/usr/bin/env bash
# Encrypts backend-secrets for one environment into a SealedSecret that is safe to commit.
# Only the sealed-secrets controller in the target cluster can decrypt it.
#
# Usage:  k8s/scripts/seal-backend-secrets.sh <test|prod> <path/to/plain.env>
# The env file holds KEY=value lines: JWT_SECRET, MONGO_URI, RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET,
# ADMIN_EMAIL, ADMIN_PASSWORD, CONSENT_IP_SALT; any extra KEY=value lines are sealed too (for example
# PLATFORM_ADMIN_EMAIL, PLATFORM_ADMIN_PASSWORD and TENANT_SECRET_<REF> for multi-tenancy, or the optional
# MARKETING_KEYS for server-side marketing conversions, ADR-029). Keep it outside the repo (or name it *.secret.env, which git ignores)
# and delete it afterwards. The plaintext never touches disk here: it is piped straight into kubeseal.
set -euo pipefail

env="${1:?usage: $0 <test|prod> <plain.env>}"
file="${2:?usage: $0 <test|prod> <plain.env>}"
case "$env" in
  test) ns=test-env ;;
  prod) ns=prod-env ;;
  *) echo "environment must be test or prod" >&2; exit 1 ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/overlays/$env/backend-secrets.sealed.yaml"

for key in JWT_SECRET MONGO_URI RAZORPAY_KEY_ID RAZORPAY_KEY_SECRET ADMIN_EMAIL ADMIN_PASSWORD CONSENT_IP_SALT; do
  grep -q "^$key=." "$file" || { echo "missing $key in $file" >&2; exit 1; }
done

kubectl create secret generic backend-secrets -n "$ns" --from-env-file="$file" --dry-run=client -o yaml \
  | kubeseal --format yaml --controller-namespace kube-system --controller-name sealed-secrets-controller \
  > "$out"
echo "→ wrote $out"

if ! grep -q 'backend-secrets.sealed.yaml' "$root/overlays/$env/kustomization.yaml"; then
  echo "→ add it to k8s/overlays/$env/kustomization.yaml under resources:"
  echo "    - backend-secrets.sealed.yaml"
fi
