#!/usr/bin/env bash
# Phase 16: checks the RUNNING platform on the kind cluster (k8s/scripts/up.sh):
# workloads healthy, pods locked down, secrets as files only, network policies enforced,
# traffic in only through the NodePorts, metrics and autoscaling working.
set -uo pipefail

NS=banking
JAVA=(user-service account-service payment-service transaction-service notification-service api-gateway)
BACKENDS=(user-service account-service payment-service transaction-service notification-service)
FAILED=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILED=1; }
check() { if eval "$2" >/dev/null 2>&1; then pass "$1"; else fail "$1"; fi; }

# Tries a TCP connection FROM INSIDE a running pod (the Java images have bash): prints open/blocked.
from_pod() {   # from_pod <deployment> <host> <port>
  kubectl -n $NS exec "deploy/$1" -- bash -c "timeout 3 bash -c '</dev/tcp/$2/$3' 2>/dev/null && echo open || echo blocked" | tr -d '\r'
}
# The same from a new pod that belongs to no service (labels app=intruder). Found live: a test pod
# must NOT wear a service's labels. With app.kubernetes.io/name=payment-service it became part of
# that Deployment for the HPA (no CPU request → "missing request for cpu", autoscaling stopped) and
# of the Service (real traffic could be sent to it). Labels ARE the identity for selectors.
# Also found live: kind's policy engine lets the FIRST connection of a brand-new pod through
# (rules are applied a moment after the pod starts), so the probe waits 2 s. See documentation/16.
from_intruder() {   # from_intruder <host> <port>
  local name="intruder-$RANDOM"
  kubectl -n $NS run "$name" --rm -i --quiet --restart=Never --labels=app=intruder \
    --image=banking/api-gateway:local --image-pull-policy=Never \
    --overrides='{"spec":{"automountServiceAccountToken":false,"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"'"$name"'","image":"banking/api-gateway:local","imagePullPolicy":"Never","command":["bash","-c","sleep 2; timeout 3 bash -c \"</dev/tcp/'"$1"'/'"$2"'\" 2>/dev/null && echo open || echo blocked"],"resources":{"requests":{"cpu":"10m","memory":"32Mi"},"limits":{"memory":"64Mi"}},"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' \
    2>/dev/null | tr -d '\r' | tail -n 1
}

echo "Workloads"
for d in $(kubectl -n $NS get deployments -o name); do
  check "$d: all replicas available" \
    "[ \"\$(kubectl -n $NS get $d -o jsonpath='{.status.availableReplicas}')\" = \"\$(kubectl -n $NS get $d -o jsonpath='{.spec.replicas}')\" ]"
done
check "postgres and kafka StatefulSets ready" \
  "[ \"\$(kubectl -n banking-infra get sts -o jsonpath='{range .items[*]}{.status.readyReplicas}{end}')\" = 11 ]"
check "keycloak available" "kubectl -n banking-infra wait --for=condition=available deployment/keycloak --timeout=5s"
check "kafka-topics Job complete" "kubectl -n banking-infra wait --for=condition=complete job/kafka-topics --timeout=5s"
check "no pod restarted in the last run (app namespace)" \
  "[ -z \"\$(kubectl -n $NS get pods -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{\"\\n\"}{end}' | grep -v '^0\$')\" ]"

echo "Pod security"
for ns in banking banking-infra banking-monitoring; do
  check "namespace $ns enforces the 'restricted' Pod Security profile" \
    "[ \"\$(kubectl get ns $ns -o jsonpath='{.metadata.labels.pod-security\\.kubernetes\\.io/enforce}')\" = restricted ]"
done
check "a privileged pod is REJECTED by the API server" \
  "! kubectl -n $NS run psa-test --image=banking/api-gateway:local --restart=Never --dry-run=server --overrides='{\"spec\":{\"containers\":[{\"name\":\"x\",\"image\":\"banking/api-gateway:local\",\"securityContext\":{\"privileged\":true}}]}}'"
for s in "${JAVA[@]}"; do
  check "$s runs as uid 10001" "[ \"\$(kubectl -n $NS exec deploy/$s -- id -u)\" = 10001 ]"
  check "$s has a read-only root filesystem" "! kubectl -n $NS exec deploy/$s -- touch /app/hacked"
  check "$s has no Kubernetes API token" "! kubectl -n $NS exec deploy/$s -- ls /var/run/secrets/kubernetes.io/serviceaccount"
  check "$s has no password/secret in its environment" "! kubectl -n $NS exec deploy/$s -- env | grep -qiE 'password|secret'"
done
check "account-service reads its DB password from a file (/run/secrets)" \
  "kubectl -n $NS exec deploy/account-service -- test -s /run/secrets/ACCOUNT_DB_PASSWORD"
check "account-service can NOT see payment-service's client secret" \
  "! kubectl -n $NS exec deploy/account-service -- test -e /run/secrets/PAYMENT_SERVICE_CLIENT_SECRET"

echo "Network policies (connections tried from inside the pods)"
USER_POD_IP="$(kubectl -n $NS get pod -l app.kubernetes.io/name=user-service -o jsonpath='{.items[0].status.podIP}')"
check "api-gateway → user-service:8081 open (an arrow of the architecture)" "[ \"\$(from_pod api-gateway user-service 8081)\" = open ]"
check "api-gateway → postgres blocked (the gateway has no database)" "[ \"\$(from_pod api-gateway postgres.banking-infra 5432)\" = blocked ]"
check "api-gateway → user-service:9000 Actuator blocked (Prometheus only)" "[ \"\$(from_pod api-gateway $USER_POD_IP 9000)\" = blocked ]"
check "payment-service → account-service:8082 open" "[ \"\$(from_pod payment-service account-service 8082)\" = open ]"
check "payment-service → user-service:8081 blocked (no such arrow)" "[ \"\$(from_pod payment-service user-service 8081)\" = blocked ]"
check "account-service → postgres open (owns account_db)" "[ \"\$(from_pod account-service postgres.banking-infra 5432)\" = open ]"
check "notification-service → postgres blocked (no database)" "[ \"\$(from_pod notification-service postgres.banking-infra 5432)\" = blocked ]"
check "notification-service → kafka open" "[ \"\$(from_pod notification-service kafka.banking-infra 9092)\" = open ]"
check "new pod in the namespace → account-service:8082 blocked" "[ \"\$(from_intruder account-service 8082)\" = blocked ]"
check "new pod in the namespace → keycloak:8180 blocked (egress denied by default)" "[ \"\$(from_intruder keycloak.banking-infra 8180)\" = blocked ]"

echo "Traffic in"
check "gateway answers on :8080 (401 without token)" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/accounts)\" = 401 ]"
check "gateway returns an X-Trace-Id" "curl -sI localhost:8080/api/accounts | grep -qi '^x-trace-id'"
check "frontend serves deep links on :4200" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' localhost:4200/payments)\" = 200 ]"
check "frontend sends a Content-Security-Policy" "curl -sI localhost:4200/ | grep -qi '^content-security-policy'"
check "Keycloak realm reachable on :8180 with the expected issuer" \
  "[ \"\$(curl -s localhost:8180/realms/banking/.well-known/openid-configuration | jq -r .issuer)\" = http://localhost:8180/realms/banking ]"
for port in 8081 8082 8083 8084 8085 9000; do
  check "port $port is NOT reachable from the host" "! curl -s -m 2 localhost:$port"
done

echo "Observability and scaling"
PODS="$(kubectl -n $NS get pods -l 'app.kubernetes.io/component in (backend,edge)' --field-selector=status.phase=Running --no-headers | wc -l | tr -d ' ')"
check "Prometheus discovered and scrapes all $PODS Spring pods (port 9000)" \
  "[ \"\$(curl -s localhost:9090/api/v1/targets | jq '[.data.activeTargets[] | select(.labels.job==\"banking-services\" and .health==\"up\")] | length')\" = $PODS ]"
check "Prometheus loaded the Phase 14 alert rules" \
  "[ \"\$(curl -s localhost:9090/api/v1/rules | jq '[.data.groups[].rules[]] | length')\" -ge 8 ]"
for h in api-gateway payment-service; do
  check "HPA $h sees CPU metrics" \
    "kubectl -n $NS get hpa $h -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' | grep -qE '^[0-9]+\$'"
  check "PDB $h allows a disruption (2+ pods ready)" \
    "[ \"\$(kubectl -n $NS get pdb $h -o jsonpath='{.status.disruptionsAllowed}')\" -ge 1 ]"
done

echo
if [ $FAILED = 0 ]; then echo "All checks passed."; else echo "Some checks FAILED."; exit 1; fi
