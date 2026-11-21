# Phase 16: Kubernetes

## 1. Concepts

Phase 15 ran the platform on **one machine** with Compose. Phase 16 runs the same images on **Kubernetes**: a cluster of nodes that keeps the declared state true, replacing crashed pods, rolling out new versions without downtime, scaling on load and enforcing who may talk to whom.

```bash
BUILD=1 k8s/scripts/up.sh       # kind cluster (3 nodes) + images + Secrets from .env + deploy
k8s/scripts/smoke-test.sh       # 68 checks
```

| Term | Meaning here |
|---|---|
| **Pod** | One running instance of a service (here one container), with its own IP. Disposable: replaced, never repaired |
| **Deployment** | "Keep N identical pods of this template running", with rolling updates between versions |
| **StatefulSet** | For Postgres and Kafka: stable name (`postgres-0`) and its own **PersistentVolumeClaim** that survives restarts |
| **Service** | Stable DNS name + virtual IP in front of the **ready** pods matching a label selector (`account-service:8082`) |
| **Probe** | Kubelet checks: *startup* (booted yet?), *liveness* (stuck? → restart), *readiness* (send traffic? → in/out of the Service) |
| **ConfigMap / Secret** | Non-secret settings / credentials, injected as env variables or **files** |
| **NetworkPolicy** | Firewall rules between pods, selected by labels (L3/L4: IPs and ports, not URL paths) |
| **Pod Security Admission** | Namespace-level rule set; `restricted` refuses pods that could run as root or escalate privileges |
| **HPA** | HorizontalPodAutoscaler: changes a Deployment's replica count from CPU usage (metrics-server) |
| **PDB** | PodDisruptionBudget: "at least 1 must stay available" during voluntary disruptions (node drain) |
| **Kustomize** | Plain YAML + overlays: `base` (environment-neutral) patched by `overlays/kind` (this laptop) |
| **kind** | "Kubernetes in Docker": each node is a container. Real Kubernetes (v1.37), local |

### What runs where

```
 localhost (kind port mappings → NodePorts)      namespace banking            (Pod Security: restricted)
 ───────────────────────────────────────         ─────────────────────────────────────────────────────────
 :4200  ──► frontend ×2                            api-gateway ×2..4 (HPA) ──► user-service ×1*
 :8080  ──► api-gateway ─────────────────────►                               account-service ×2
 :8180  ──► keycloak                                                         payment-service ×2..4 (HPA) ──► account
 :9090  ──► prometheus                                                       transaction-service ×1*  notification-service ×1*
 :16686 ──► jaeger UI                              * 2 in the base; 1 in the kind overlay (laptop memory)

 namespace banking-infra (restricted)            namespace banking-monitoring (restricted)
 postgres-0 (PVC)  kafka-0 (PVC)  kafka-topics Job   prometheus (discovers pods via the API)  jaeger
 keycloak                                         ▲ scrapes port 9000 of every Spring pod

 nodes: banking-control-plane │ banking-worker (pool=data: postgres, kafka) │ banking-worker2 (pool=apps)
```

## 2. Why each design decision

| Decision | Why |
|---|---|
| **Same images as Phase 15**, config only through env + files | "Build once, run anywhere": the image that passed the tests is the one that runs. Nothing Kubernetes-specific inside it |
| **Actuator on its own port (9000) on Kubernetes** | A NetworkPolicy filters ports, not paths. Now Prometheus may reach 9000 and nothing else; callers reach only the API port. Activated by `on-cloud-platform: kubernetes`, so Compose and IDE runs are unchanged |
| **Probes on the API port (`/livez`, `/readyz`)** | A healthy management port says nothing about a stuck API connector. Liveness never checks the DB: a DB outage must not restart every pod |
| **startupProbe** (up to 3 min) | Liveness can be strict (30 s) without killing a JVM that is still running Flyway |
| **preStop sleep 5 s** | Endpoint removal and SIGTERM happen in parallel. Measured: without it, **20 of 891** requests failed during rollouts; with it, **0 of 1,602** |
| **maxSurge 1, maxUnavailable 0** | A new pod must be *ready* before an old one goes |
| **Memory request = limit; CPU request, no CPU limit** | The JVM sizes its heap from the limit (75 %, Phase 15). CPU limits throttle JVM startup and GC; memory is what hurts neighbours |
| **`restricted` Pod Security on all three namespaces** | The API server refuses a pod that could run as root. Even Postgres (uid 70), Kafka and Keycloak (uid 1000) comply |
| **readOnlyRootFilesystem + `/tmp` emptyDir** | An attacker can't drop a binary or modify the app. Spring needs only `/tmp` |
| **`automountServiceAccountToken: false`** | The services never call the Kubernetes API: no token to steal. Only Prometheus has one (Role: list pods in `banking`) |
| **Secrets as files** (`/run/secrets`, configtree), one Secret per service | Same mechanism as Compose; not visible in `kubectl describe` or `/proc/*/environ`. account-service can't mount payment-service's client secret |
| **Default-deny NetworkPolicies, ingress AND egress** | Only the arrows of the architecture diagram are open. A compromised gateway can't open a DB connection |
| **3 namespaces** | Trust zones with their own policies; cross-namespace rules name the namespace explicitly |
| **Kustomize base + overlay** | The base is the production shape (2 replicas everywhere, PDBs). The kind overlay holds this laptop's compromises (NodePorts, `local` tag, 1 replica for 3 read-side services) |
| **ConfigMaps with content hash** (`banking-java-config-8db84d5hf5`) | Change the config → new name → Deployments roll automatically. No "forgot to restart" drift |
| **HPA only on gateway and payment-service, min 2** | The hot paths. Min 2: one pod is a single point of failure during a rollout or node loss |
| **PDB minAvailable 1** for every 2+ replica Deployment | `kubectl drain` waits instead of evicting the last pod. Measured: drain with PDBs and 2 replicas, **0 of 801** requests failed |
| **Data layer first** (`up.sh` waits for Postgres, Kafka, topics, Keycloak) | Otherwise the services crash-loop with growing back-off until the DB is there (that works too, just slower) |
| **Stateful infra in-cluster, for local only** | Production: managed Postgres/Kafka/IdP. The apps see only host names and Secrets, so swapping is a ConfigMap change |

## 3. Architecture: manifests

```
k8s/
├── kind/cluster.yaml                  3 nodes; localhost:8080/4200/8180/9090/16686 → NodePorts
├── base/                              environment-neutral (the production shape)
│   ├── namespaces.yaml                banking, banking-infra, banking-monitoring + Pod Security labels
│   ├── infra/                         postgres (StatefulSet), kafka (StatefulSet + topics Job), keycloak,
│   │                                  network policies; ConfigMaps generated from infrastructure/ files
│   ├── monitoring/                    prometheus (+ RBAC, pod discovery config), jaeger, network policies
│   └── apps/                          6 Spring Boot Deployments + Services, frontend, java-config.env,
│                                      autoscaling.yaml (2 HPAs, 7 PDBs), network-policies.yaml (11)
├── overlays/kind/                     image tag "local", NodePorts, data pool, 1-replica laptop patch
└── scripts/                           up.sh, down.sh, create-secrets.sh, smoke-test.sh, load.sh
```

The realm JSON, Postgres init scripts, Kafka topics script and alert rules are **the same files Compose uses** (`infrastructure/`). Kustomize reads files outside its folder only with `--load-restrictor LoadRestrictionsNone`, which `up.sh` passes.

## 4. Request flow

**A transfer from the browser:**

1. Browser → `localhost:8080` → kind port mapping → NodePort 30080 on the control-plane node → kube-proxy → one of the **ready** api-gateway pods (any node).
2. NetworkPolicy `entry-points` admits it (port 8080 from anywhere). Gateway validates the JWT (keys from `keycloak.banking-infra:8180`, allowed by `spring-to-keycloak-and-jaeger`).
3. Gateway → `http://payment-service:8083` (Service DNS) → a ready payment pod. Allowed by `gateway-to-backends` (egress) **and** `backends-from-gateway` (ingress): both sides must agree.
4. payment-service → `account-service:8082` (`payment-to-account` + `account-from-payment`), → `postgres.banking-infra:5432` (`database-owners-to-postgres` + infra `postgres`), → `kafka.banking-infra:9092` via the outbox.
5. Every hop sends spans to `jaeger.banking-monitoring:4318`. Prometheus scrapes `:9000` of every pod it discovered.

**A rolling update** (`kubectl rollout restart deployment/account-service`, or a new image/config):

```
new pod created ── startupProbe /livez ok ── readinessProbe /readyz ok ──► added to Service endpoints
                                                                                   │
old pod: Terminating ──► removed from endpoints ─┐ (in parallel)                   │
                         SIGTERM after preStop 5 s: Spring stops accepting, finishes in-flight (≤ 20 s)
                         SIGKILL only if still running at 45 s (terminationGracePeriodSeconds)
```

## 5. Project structure (new and changed)

| Path | What |
|---|---|
| `k8s/**` | New: cluster, Kustomize base + overlay, scripts (see section 3) |
| `*/src/main/resources/application.yml` | New last document `on-cloud-platform: kubernetes`: management port 9000, `/livez` `/readyz` on the API port. Gateway: connection pool max idle 15 s / life 60 s |
| `*/security/*SecurityConfig.java` | `/livez`, `/readyz` permitted (the allow-list ends with `denyAll()`) |
| `*/observability/ObservabilityConfig.java` | Probes get no trace and no request metric (`isProbeOrActuator`) |
| `*/src/test/.../KubernetesPortsTest.java` | New, 6 × 2 tests: probes on the API port without a token and unobserved; metrics only on the management port, 404 on the API port |
| `README.md` | Phase 16 row, Kubernetes quick start, layout |

## 6. Important code

### Spring Boot: what changes on Kubernetes

```yaml
---
spring:
  config:
    activate:
      on-cloud-platform: kubernetes      # detected from KUBERNETES_SERVICE_HOST, present in every pod
management:
  server:
    port: 9000                           # Actuator on its own port
  endpoint:
    health:
      probes:
        add-additional-paths: true       # /livez and /readyz on the API port too
```

### A Deployment (account-service, abridged)

```yaml
spec:
  replicas: 2
  strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 1, maxUnavailable: 0 } }
  template:
    metadata:
      labels: { app.kubernetes.io/name: account-service, app.kubernetes.io/component: backend }
      annotations: { prometheus.io/scrape: "true", prometheus.io/path: /actuator/prometheus }
    spec:
      automountServiceAccountToken: false
      enableServiceLinks: false
      terminationGracePeriodSeconds: 45
      securityContext: { runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, fsGroup: 10001,
                         seccompProfile: { type: RuntimeDefault } }
      topologySpreadConstraints: [{ maxSkew: 1, topologyKey: kubernetes.io/hostname, whenUnsatisfiable: ScheduleAnyway, ... }]
      containers:
        - name: account-service
          image: banking/account-service            # tag from the overlay
          ports: [{ name: http, containerPort: 8082 }, { name: management, containerPort: 9000 }]
          envFrom: [{ configMapRef: { name: banking-java-config } }]
          resources: { requests: { cpu: 250m, memory: 512Mi }, limits: { memory: 512Mi } }
          startupProbe:   { httpGet: { path: /livez,  port: http }, periodSeconds: 5, failureThreshold: 36 }
          livenessProbe:  { httpGet: { path: /livez,  port: http }, periodSeconds: 10, failureThreshold: 3 }
          readinessProbe: { httpGet: { path: /readyz, port: http }, periodSeconds: 5, failureThreshold: 2 }
          lifecycle: { preStop: { sleep: { seconds: 5 } } }
          securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: [ALL] } }
          volumeMounts:
            - { name: tmp, mountPath: /tmp }
            - { name: secrets, mountPath: /run/secrets, readOnly: true }
      volumes:
        - { name: tmp, emptyDir: { sizeLimit: 64Mi } }
        - { name: secrets, secret: { secretName: account-service-secrets, defaultMode: 0440 } }
```

### A NetworkPolicy pair: payment-service → account-service

```yaml
# egress at the caller                              # ingress at the callee
spec:                                               spec:
  podSelector:                                        podSelector:
    matchLabels: { app.kubernetes.io/name: payment-service }   matchLabels: { app.kubernetes.io/name: account-service }
  policyTypes: [Egress]                               policyTypes: [Ingress]
  egress:                                             ingress:
    - to: [{ podSelector: { matchLabels:                - from: [{ podSelector: { matchLabels:
          { app.kubernetes.io/name: account-service } } }]     { app.kubernetes.io/name: payment-service } } }]
      ports: [{ port: 8082, protocol: TCP }]              ports: [{ port: 8082, protocol: TCP }]
```

### Prometheus finds pods by itself

```yaml
kubernetes_sd_configs: [{ role: pod, namespaces: { names: [banking] } }]
relabel_configs:
  - { source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape], regex: "true", action: keep }
  - { source_labels: [__meta_kubernetes_pod_container_port_name], regex: management, action: keep }   # 9000 only
  - { source_labels: [__meta_kubernetes_pod_name], target_label: instance }
```

### Secrets from `.env`, never through the command line

```bash
{ printf '%s=%s\n' ACCOUNT_DB_PASSWORD "$(env_value ACCOUNT_DB_PASSWORD)"; } \
  | kubectl -n banking create secret generic account-service-secrets --from-env-file=/dev/stdin \
      --dry-run=client -o yaml | kubectl apply -f -
```

## 7. How services communicate (in Kubernetes)

| From → to | Address | Allowed by |
|---|---|---|
| browser → gateway / frontend / Keycloak | `localhost:8080` / `:4200` / `:8180` (NodePorts) | `entry-points`, infra `keycloak` |
| gateway → services | `user-service:8081` … `notification-service:8085` (same namespace: short name) | `gateway-to-backends` + `backends-from-gateway` (port range 8081–8085) |
| payment → account | `account-service:8082` | `payment-to-account` + `account-from-payment` |
| services → Postgres | `postgres.banking-infra.svc.cluster.local:5432` | only user/account/payment/transaction (both sides) |
| services → Kafka | `kafka.banking-infra.svc.cluster.local:9092` (also the advertised listener) | the five backends |
| everyone Spring → Keycloak keys, Jaeger | `keycloak.banking-infra…:8180`, `jaeger.banking-monitoring…:4318` | `spring-to-keycloak-and-jaeger` |
| Prometheus → pods | pod IP `:9000` (discovered) | `metrics-from-prometheus` |
| any pod → DNS | CoreDNS in `kube-system` | `allow-dns` (without it, default-deny breaks every name lookup) |

Token issuer: still `http://localhost:8180/realms/banking` (Keycloak's fixed `KC_HOSTNAME`), keys fetched over the cluster network, exactly as in Compose.

## 8. Run it

```bash
docker compose --profile app down        # free ports 8080/4200/8180/9090/16686 and memory (data volumes stay)
BUILD=1 k8s/scripts/up.sh                # first run: pulls Postgres/Kafka/Keycloak/... into the nodes (slow once)
k8s/scripts/smoke-test.sh

kubectl -n banking get pods,svc,hpa,pdb -o wide
kubectl -n banking logs deploy/payment-service -f
kubectl top pods -n banking

# zero-downtime rollout under load
k8s/scripts/load.sh 120 &                # 8 reads/s over all services + 1 transfer/s, then a failure report
kubectl -n banking rollout restart deployment/account-service
kubectl -n banking rollout status deployment/account-service
kubectl -n banking rollout undo deployment/account-service      # back to the previous ReplicaSet

# self-healing and maintenance
kubectl -n banking delete pod <payment-pod> --grace-period=0 --force     # crash
kubectl drain banking-worker2 --ignore-daemonsets --delete-emptydir-data # maintenance
kubectl uncordon banking-worker2

# autoscaling
USERS="alice bob" READS=50 TRANSFERS=0 k8s/scripts/load.sh 150 &
kubectl -n banking get hpa -w

# after a code change
BUILD=1 k8s/scripts/up.sh && kubectl -n banking rollout restart deployment   # same tag "local": no automatic rollout

k8s/scripts/down.sh                      # delete the cluster
```

API calls are the same as before (through `localhost:8080`), e.g.:

```bash
TOKEN=$(./infrastructure/keycloak/pkce-login.sh alice 2>/dev/null)
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/accounts | jq
curl -sI localhost:8080/api/accounts | grep -i x-trace-id
```

## 9. Real test results

kind v0.33.0, Kubernetes v1.37.0, 1 control plane + 2 workers, Docker Desktop with 8 GB.

| Check | Result |
|---|---|
| Smoke test | **68/68**: workloads, Pod Security (a privileged pod is rejected), uid 10001, read-only root, no API token, no secret in env, secrets as files per service, 10 network-policy probes (allowed arrows open, everything else blocked), NodePorts, Prometheus discovery, HPA metrics, PDBs |
| **e2e (Playwright, real Chrome) against the cluster** | **9/9** |
| Backend tests | **284/284** (272 + 12 new `KubernetesPortsTest`) |
| Start-up | Spring Boot pod ready in ~3 s ("Started AccountServiceApplication in 2.961 seconds"); redeploy incl. image rebuild 45 s |
| First deploy | dominated by image pulls into the nodes: 1–9 min per third-party image on this network |
| **Rolling restart of all 7 Deployments under load** | **1,602 requests (178 transfers), 0 failed** |
| Same, **without preStop** (control experiment) | **20 of 891 failed**: 18 × connection refused (NodePort still routed to the stopping gateway), 2 × 502 (gateway → stopping account-service); 3 of them transfers, whose outcome the client can't know (→ retry with the same Idempotency-Key) |
| SIGKILL of a payment pod, then of a gateway pod, under load | 0 failed (the second replica took over) |
| Node drain, 3 services with **1 replica** | **20 × 502 within 5 s** for user- and transaction-service: eviction removes the only pod from the Service before the new one is ready |
| Node drain, **production shape** (2 replicas + PDB each) | **801 requests, 0 failed**; drain logged "Cannot evict pod as it would violate the pod's disruption budget" for the 2nd payment and frontend pod until their replacements were ready |
| HPA | gateway **2 → 3 → 4** at 92 % CPU; 14,800 requests in 150 s, 0 × 5xx |
| Money | all **531** load-test transfers `COMPLETED`, none stuck, after crashes, drains and rollouts |
| Postgres connections | 87 in a rollout storm (limit 100, new pods failed) → **40** steady state after the budget |
| Memory | ~5.3 GB for the whole cluster (laptop shape); Spring pods 225–340 MiB of 512 |
| Observability | 8/8 pods scraped on :9000 (discovered, not listed); 8 alert rules loaded; traces from all 6 services in Jaeger |

### Found along the way

| Finding | Fix |
|---|---|
| `kind load`: "content digest … not found" for Postgres/Kafka/… | Docker Desktop stores multi-platform images for one platform only; one failing image aborts the whole load. Load only our images (to the workers); nodes pull third-party images |
| All Spring pods crash-looped: `AccessDeniedException: /run/secrets/ACCOUNT_DB_PASSWORD` | Secret files are owned by root; mode 0400 = owner only. `defaultMode: 0440` + `fsGroup: 10001` |
| A test pod labelled `app.kubernetes.io/name=payment-service` broke the HPA ("missing request for cpu in container np-probe") | Labels are identity: the pod joined the Deployment's selector (HPA, Service, PDB). Network tests now run **inside** the real pods; the "intruder" wears no service label |
| A brand-new pod's **first** connection passed the egress deny (3/3 runs: open, blocked, blocked, …) | kind's policy engine applies rules a moment after the pod starts. Defense in depth matters: every service still demands a JWT, the DB a password. Calico/Cilium can hold a pod until its policy is in place |
| New pods failed: "remaining connection slots are reserved for roles with the SUPERUSER attribute" | Each pod holds its full Hikari pool (10). Pods × pool > `max_connections`. Pool 5 per pod, Keycloak pool 10: worst case 80 < 100 |
| Restarting all Deployments at once (with 3 temporarily doubled) starved the 8 GB VM: API server "TLS handshake timeout", pods restarted | Roll one at a time: `kubectl rollout pause` all, apply, `resume` one by one |
| `kubectl apply --server-side` refused: "conflict with kubectl … subresource scale: .spec.replicas" | Server-side apply protects fields owned by someone else (a manual `kubectl scale`). Git is the source of truth: `--force-conflicts` |
| HPA-managed Deployments stayed at 0 after `scale --replicas=0` | An HPA doesn't act on a target at 0 replicas (that's how you pause it). Scale back to `minReplicas` |
| Jaeger: ~2 traces/s of `GET /livez` and `/readyz` | Probes moved to the API port, past the `/actuator` exclusion. `isProbeOrActuator` excludes them (tested) |
| ~30 req/s per user got through a 10 req/s rate limit with 4 gateway pods | The limiter is in memory per pod: N replicas = N × the limit. Production: Redis-backed limiter or edge limits |
| `up.sh` restarted metrics-server on every run | Re-applying the upstream manifest dropped the `--kubelet-insecure-tls` flag, patched again → rollout. Install once |
| After `uncordon` all pods stayed on one worker | Kubernetes never moves running pods. Rebalance by rollout (or the descheduler) |
| Prevented, not hit: Kafka in a Service named `kafka` gets `KAFKA_PORT=tcp://…`, which the image turns into a bogus broker setting | `enableServiceLinks: false` (on every pod) |

## 10. Common errors and troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ErrImageNeverPull` / `ImagePullBackOff` for `banking/*` | Image not loaded into the nodes: `BUILD=1 k8s/scripts/up.sh` (or `kind load docker-image … --nodes …`) |
| Code change not running | Same tag `local` → pod template unchanged → no rollout. `kubectl -n banking rollout restart deployment/<svc>`. Production: immutable tags (git SHA) |
| `CrashLoopBackOff` | `kubectl -n banking logs <pod> --previous`. DB not there yet (it heals), wrong secret, Flyway error |
| `CreateContainerConfigError` / pod stuck `ContainerCreating` | Secret or ConfigMap missing: `kubectl -n banking describe pod <pod>`; run `k8s/scripts/create-secrets.sh` |
| `AccessDeniedException /run/secrets/...` | Secret file mode/ownership: `defaultMode: 0440` + `fsGroup` |
| Pod restarted by liveness while booting | Missing/short startupProbe; check `Last State` in `describe pod` |
| Pod `Running` but `0/1` | Readiness failing: `/readyz` 401 → not permitted in SecurityConfig; or the app is shutting down |
| Requests time out between services | NetworkPolicy: both sides need a rule (egress at caller, ingress at callee) and DNS egress. Test from inside: `kubectl exec deploy/api-gateway -- bash -c '</dev/tcp/user-service/8081'` |
| Prometheus target missing | Pod annotation `prometheus.io/scrape`, port **named** `management`, `metrics-from-prometheus` policy |
| `pods "x" is forbidden: violates PodSecurity "restricted"` | Missing `runAsNonRoot`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]` or `seccompProfile` |
| HPA `cpu: <unknown>` | metrics-server not ready, pods without CPU requests, or a foreign pod matching the selector |
| `kubectl drain` hangs | A PDB with `disruptionsAllowed: 0` (1 replica + minAvailable 1) |
| "remaining connection slots are reserved" | Pods × Hikari pool > Postgres `max_connections`; recompute the budget or add PgBouncer |
| 401 for every request | Issuer mismatch: `KEYCLOAK_ISSUER_URI` must be `http://localhost:8180/realms/banking` (what's in the token) |
| `up.sh` port already in use | The Compose stack is still running: `docker compose --profile app down` |
| Everything slow, API server timeouts | The node VM is out of memory: `docker stats`; fewer replicas, one rollout at a time |

## 11. Production notes

- **Managed data services** (RDS/Cloud SQL with PITR, MSK/Confluent, a hosted IdP or the Keycloak Operator with `start` + TLS + 2 replicas). Postgres behind **PgBouncer** when pods × pool grows.
- **Ingress:** a cloud load balancer + **Gateway API** (`Gateway` + `HTTPRoute`, e.g. Envoy Gateway) with TLS from cert-manager, instead of NodePorts. The Spring Cloud Gateway stays the application gateway behind it.
- **Images** pinned by digest from a registry, signed (cosign) and verified by an admission policy (Kyverno / Sigstore policy-controller); vulnerability scans in CI.
- **GitOps** (Argo CD / Flux): the cluster pulls the overlay from git, drift is corrected automatically, rollbacks are `git revert`. Progressive delivery (Argo Rollouts: canary with Prometheus analysis).
- **Secrets:** External Secrets Operator or the Secrets Store CSI driver from Vault / a cloud secret manager; etcd encryption at rest; RBAC on `get secrets`; rotation.
- **Network:** a CNI that enforces policies from the first packet (Calico, Cilium); L7 rules or a service mesh (mTLS with real workload identity: SPIFFE) when labels alone aren't enough.
- **Availability:** 2+ replicas everywhere (the base already is), spread across **zones** (`topology.kubernetes.io/zone`), PDBs, cluster autoscaler, HPA on custom metrics (request rate, Kafka lag via KEDA).
- **Observability:** Prometheus Operator (ServiceMonitor/PodMonitor), Alloy as a DaemonSet shipping pod logs to Loki, long-term metrics (Mimir/Thanos).
- **Policy:** ResourceQuota + LimitRange per namespace; Kyverno/Gatekeeper rules (no `latest`, requests required, no privileged).
- **Rate limiting** shared across gateway replicas (Redis) or at the edge.

## 12. Interview questions

1. Deployment vs StatefulSet: why are Postgres and Kafka StatefulSets? What happens to their data when the pod moves?
2. Startup, liveness and readiness probes: what does each do on failure? Why must liveness not check the database?
3. Why can a rolling update drop requests even with graceful shutdown? What exactly does the preStop sleep fix? (Measured here: 20 vs 0 failures.)
4. `maxSurge` / `maxUnavailable`: what do 1/0 guarantee, and what do they cost?
5. Requests vs limits for a JVM: why memory request = limit, and why many teams set no CPU limit?
6. How does the HPA compute the desired replicas? Why does the target refer to the CPU *request*? Why minReplicas 2?
7. What does a PodDisruptionBudget protect against, and what not? Why can't a 1-replica Deployment have `minAvailable: 1`?
8. NetworkPolicies: why default-deny? Why do you need both an egress and an ingress rule, and a DNS rule? Why can't a policy restrict `/actuator/prometheus` on the API port?
9. Labels are the identity for NetworkPolicies and Services. What does that mean for security? What gives stronger workload identity?
10. Secrets: env vars vs mounted files vs an external secret store. Why is a Kubernetes Secret not encrypted by default?
11. What does the `restricted` Pod Security profile enforce? Why readOnlyRootFilesystem and `automountServiceAccountToken: false`?
12. Kustomize base vs overlay: what belongs where? Why does a ConfigMap get a hash suffix?
13. Why must a Deployment managed by an HPA not set `replicas` in git? What does server-side apply do on a field conflict?
14. Pods × connection pool × surge: how do you size Hikari pools for a scaled service? When do you add PgBouncer?
15. A transfer request failed with "connection refused" during a rollout. Is it safe for the client to retry? What makes it safe here?
16. Why does an in-memory rate limiter break when you scale the gateway? How would you fix it?
