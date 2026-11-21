#!/usr/bin/env bash
# Phase 16: from nothing to the running platform on a local kind cluster. Safe to re-run.
#
#   k8s/scripts/up.sh             cluster (if missing) + images + secrets + deploy
#   BUILD=1 k8s/scripts/up.sh     rebuild the images first (docker compose --profile app build)
#
# Needs: docker, kind, kubectl, and the Compose stack STOPPED (same host ports, and memory).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER=banking
TAG=local
METRICS_SERVER_VERSION=v0.8.0
APP_IMAGES=(user-service account-service payment-service transaction-service notification-service api-gateway frontend)
# Workloads run on the workers only (the control plane is tainted NoSchedule).
NODES=banking-worker,banking-worker2
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

step "Cluster"
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "kind cluster '$CLUSTER' exists"
else
  kind create cluster --config "$ROOT/k8s/kind/cluster.yaml"
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

step "metrics-server (CPU/memory metrics for the HPA and kubectl top)"
# Installed once. (Re-applying the upstream manifest would drop the flag below and restart it every run.)
if ! kubectl -n kube-system get deployment metrics-server >/dev/null 2>&1; then
  kubectl apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/$METRICS_SERVER_VERSION/components.yaml" >/dev/null
  # kind's kubelets have self-signed serving certificates; never do this on a real cluster.
  kubectl -n kube-system patch deployment metrics-server --type=json \
    -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi
echo "metrics-server installed"

step "Images"
if [ "${BUILD:-0}" = 1 ]; then
  (cd "$ROOT" && IMAGE_TAG=$TAG docker compose --profile app build)
fi
for image in "${APP_IMAGES[@]}"; do
  docker image inspect "banking/$image:$TAG" >/dev/null 2>&1 \
    || { echo "banking/$image:$TAG not found: run with BUILD=1" >&2; exit 1; }
done
# Our images exist only locally: copy them into the workers' containerd (no registry needed).
# Third-party images (Postgres, Kafka, ...) are pulled by the nodes from their registries as usual:
# found live, "kind load" fails on multi-platform images that Docker Desktop stores for one platform only
# ("content digest ... not found"), and one failing image aborts the whole load.
kind load docker-image --name "$CLUSTER" --nodes "$NODES" $(printf "banking/%s:$TAG " "${APP_IMAGES[@]}")

step "Namespaces and Secrets"
kubectl apply -f "$ROOT/k8s/base/namespaces.yaml"
"$ROOT/k8s/scripts/create-secrets.sh"

step "Platform layer: Postgres, Kafka, Keycloak, Prometheus, Jaeger"
MANIFESTS="$(mktemp)"
trap 'rm -f "$MANIFESTS"' EXIT
kubectl kustomize --load-restrictor LoadRestrictionsNone "$ROOT/k8s/overlays/kind" > "$MANIFESTS"
# Data layer first: the services would only crash-loop (with growing back-off) until it's ready.
# Server-side apply + --force-conflicts: git is the source of truth. Found live: after a manual
# "kubectl scale", SSA refuses to reset replicas (the field now belongs to "kubectl scale").
# Fields the manifests don't set (replicas of HPA-managed Deployments) are never touched.
APPLY=(kubectl apply --server-side --force-conflicts -f "$MANIFESTS")
"${APPLY[@]}" -l banking.dev/layer=platform
kubectl -n banking-infra rollout status statefulset/postgres --timeout=180s
kubectl -n banking-infra rollout status statefulset/kafka --timeout=180s
kubectl -n banking-infra wait --for=condition=complete job/kafka-topics --timeout=180s
kubectl -n banking-infra rollout status deployment/keycloak --timeout=300s

step "Application layer"
"${APPLY[@]}"
for deployment in $(kubectl -n banking get deployments -o name); do
  kubectl -n banking rollout status "$deployment" --timeout=300s
done

cat <<'EOF'

Ready.
  App          http://localhost:4200        (alice / DEMO_USER_PASSWORD from .env)
  API          http://localhost:8080
  Keycloak     http://localhost:8180
  Prometheus   http://localhost:9090
  Jaeger       http://localhost:16686

  kubectl -n banking get pods,hpa,pdb       k8s/scripts/smoke-test.sh       k8s/scripts/down.sh
EOF
