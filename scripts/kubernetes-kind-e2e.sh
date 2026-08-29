#!/usr/bin/env bash
set -Eeuo pipefail

project="${1:?compose project is required}"
cluster="${2:?Kind cluster name is required}"
repo_root="${3:-$(pwd)}"
compose_file="$repo_root/infra/compose.e2e.yml"
compose_env="$repo_root/.kind-e2e.env"
kubeconfig="$repo_root/.kind-kubeconfig"
bundle_root="$repo_root/.kind-bundles"
tool_root="$repo_root/.kind-tools"
backend_port="${RW_E2E_BACKEND_PORT:-18081}"
api_root="http://127.0.0.1:$backend_port"
embedding_model="${RW_EMBEDDING_MODEL:-qwen3-embedding:0.6b}"
kind_node_image="${RW_KIND_NODE_IMAGE:-kindest/node:v1.36.1@sha256:3489c7674813ba5d8b1a9977baea8a6e553784dab7b84759d1014dbd78f7ebd5}"
kind_node_image_source="${RW_KIND_NODE_IMAGE_SOURCE:-kind-release}"
kind_node_image_expected_id="${RW_KIND_NODE_IMAGE_EXPECTED_ID:-}"
kubernetes_version="${RW_KUBERNETES_VERSION:-v1.36.1}"
backend_image="reasonweave-backend-e2e:$project"
kind_path=''
kubectl_path=''
kind_version='v0.32.0'
kubectl_version="$kubernetes_version"
node_image="${RW_COLLECTOR_NODE_IMAGE:-node:22-alpine}"
ollama_cache_volume='reasonweave-ollama-model-cache'

case "$project" in
  reasonweave-kind-[a-z0-9-]*) ;;
  *) echo "Refusing unexpected Compose project name: $project" >&2; exit 1 ;;
esac
case "$cluster" in
  reasonweave-[a-z0-9-]*) ;;
  *) echo "Refusing unexpected Kind cluster name: $cluster" >&2; exit 1 ;;
esac
if ! [[ "$kubernetes_version" =~ ^v1\.(35|36|37)\.[0-9]+$ ]]; then
  echo "Unsupported Kubernetes validation version: $kubernetes_version" >&2
  exit 1
fi
case "$kind_node_image_source" in
  kind-release|kubernetes-release-artifact) ;;
  *) echo "Unsupported Kind node image source: $kind_node_image_source" >&2; exit 1 ;;
esac
if [[ "$kind_node_image" != *@sha256:* ]] && [ -z "$kind_node_image_expected_id" ]; then
  echo 'A non-digest Kind node image requires RW_KIND_NODE_IMAGE_EXPECTED_ID.' >&2
  exit 1
fi
case "$(readlink -f "$repo_root")" in
  /tmp/reasonweave-kind-*) ;;
  *) echo "Refusing unexpected repository root: $repo_root" >&2; exit 1 ;;
esac

cleanup() {
  set +e
  if [ -n "$kind_path" ]; then
    sudo "$kind_path" delete cluster --name "$cluster" >/dev/null 2>&1 || true
  fi
  if [ -f "$compose_file" ]; then
    if [ -f "$compose_env" ]; then
      sudo docker compose --env-file "$compose_env" -p "$project" -f "$compose_file" \
        down --volumes --remove-orphans >/dev/null 2>&1 || true
    else
      RW_EMBEDDING_MODEL_DIGEST=unverified sudo -E docker compose -p "$project" \
        -f "$compose_file" down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
  fi
  sudo docker image rm "$backend_image" >/dev/null 2>&1 || true
  rm -f -- "$kubeconfig" "$compose_env"
  rm -rf -- "$bundle_root" "$tool_root"
}
trap cleanup EXIT

on_error() {
  local status="$?"
  echo "Kind E2E failed at line $1 while running: $2 (exit $status)" >&2
  return "$status"
}
trap 'on_error "$LINENO" "$BASH_COMMAND"' ERR

assert_json() {
  local scenario="$1"
  local stage="$2"
  local payload="$3"
  local filter="$4"
  shift 4
  if jq -e "$@" "$filter" <<<"$payload" >/dev/null; then
    return 0
  fi
  echo "Kind scenario '$scenario' failed at stage '$stage'." >&2
  if ! jq . <<<"$payload" >&2; then
    printf 'Non-JSON response (first 8192 bytes):\n%.8192s\n' "$payload" >&2
  fi
  return 1
}

for command_name in docker curl jq awk tar grep sha256sum uname; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is unavailable: $command_name" >&2
    exit 1
  }
done
case "$(uname -m)" in
  x86_64) tool_arch='amd64' ;;
  aarch64|arm64) tool_arch='arm64' ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac
mkdir -p "$tool_root"
kind_asset="kind-linux-$tool_arch"
curl --fail --silent --show-error --location \
  "https://github.com/kubernetes-sigs/kind/releases/download/$kind_version/$kind_asset" \
  --output "$tool_root/$kind_asset"
curl --fail --silent --show-error --location \
  "https://github.com/kubernetes-sigs/kind/releases/download/$kind_version/$kind_asset.sha256sum" \
  --output "$tool_root/$kind_asset.sha256sum"
(cd "$tool_root" && sha256sum --check "$kind_asset.sha256sum")
kind_path="$tool_root/kind"
mv -- "$tool_root/$kind_asset" "$kind_path"
chmod 0755 "$kind_path"

kubectl_path="$tool_root/kubectl"
curl --fail --silent --show-error --location \
  "https://dl.k8s.io/release/$kubectl_version/bin/linux/$tool_arch/kubectl" \
  --output "$kubectl_path"
kubectl_sha="$(curl --fail --silent --show-error --location \
  "https://dl.k8s.io/release/$kubectl_version/bin/linux/$tool_arch/kubectl.sha256")"
printf '%s  %s\n' "$kubectl_sha" "$kubectl_path" | sha256sum --check --status
chmod 0755 "$kubectl_path"
mem_available_kib="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
if ! printf '%s' "$mem_available_kib" | grep -Eq '^[0-9]+$' \
    || [ "$mem_available_kib" -lt 3145728 ]; then
  echo "Kind validation requires at least 3 GiB MemAvailable; found ${mem_available_kib:-unknown} KiB." >&2
  exit 1
fi

mkdir -p "$bundle_root"
cd "$repo_root"
sudo docker volume create "$ollama_cache_volume" >/dev/null
sudo docker run --rm \
  -v "$repo_root/backend:/workspace/backend" \
  -v reasonweave-test_reasonweave-maven-cache:/root/.m2 \
  -w /workspace/backend \
  maven:3.9.9-eclipse-temurin-21 \
  mvn -B -Dmaven.test.skip=true package
sudo env RW_E2E_BACKEND_PORT="$backend_port" docker compose -p "$project" -f "$compose_file" \
  up -d postgres-e2e ollama-e2e
attempt=0
while [ "$attempt" -lt 60 ]; do
  if sudo docker compose -p "$project" -f "$compose_file" exec -T ollama-e2e ollama list >/dev/null 2>&1; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done
if [ "$attempt" -ge 60 ]; then
  sudo docker compose -p "$project" -f "$compose_file" logs --tail 120 ollama-e2e || true
  echo 'Ollama did not become ready.' >&2
  exit 1
fi
sudo docker compose -p "$project" -f "$compose_file" run --rm -T ollama-model-e2e
ollama_container="$(sudo docker compose -p "$project" -f "$compose_file" ps -q ollama-e2e)"
ollama_ip="$(sudo docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{"\n"}}{{end}}' \
  "$ollama_container" | awk 'NF {print; exit}')"
model_digest="$(curl --fail --silent "http://$ollama_ip:11434/api/tags" \
  | jq -er --arg model "$embedding_model" \
      '.models[] | select(.name == $model or .model == $model) | .digest' | head -n 1)"
if ! printf '%s' "$model_digest" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "Unable to resolve a verified Ollama model digest for $embedding_model." >&2
  exit 1
fi
umask 077
printf 'RW_E2E_BACKEND_IMAGE=%s\nRW_EMBEDDING_MODEL=%s\nRW_EMBEDDING_MODEL_DIGEST=%s\n' \
  "$backend_image" "$embedding_model" "$model_digest" > "$compose_env"
sudo env RW_E2E_BACKEND_PORT="$backend_port" docker compose --env-file "$compose_env" \
  -p "$project" -f "$compose_file" up -d --build

attempt=0
while [ "$attempt" -lt 60 ]; do
  if curl --fail --silent "$api_root/actuator/health" | jq -e '.status == "UP"' >/dev/null 2>&1; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 3
done
if [ "$attempt" -ge 60 ]; then
  sudo docker compose --env-file "$compose_env" -p "$project" -f "$compose_file" \
    logs --tail 200 backend-e2e ollama-e2e || true
  echo 'ReasonWeave backend did not become healthy.' >&2
  exit 1
fi
curl --fail --silent "$api_root/api/v1/domain-packs" \
  | jq -e 'any(.data[]; .key == "kubernetes-pod-diagnostics" and .version == "1.0.0" and .ready == true)' \
  >/dev/null

if [ -n "$kind_node_image_expected_id" ]; then
  expected_image_id="$kind_node_image_expected_id"
  case "$expected_image_id" in
    sha256:*) ;;
    *) expected_image_id="sha256:$expected_image_id" ;;
  esac
  actual_image_id="$(sudo docker image inspect "$kind_node_image" --format '{{.Id}}')"
  if [ "$actual_image_id" != "$expected_image_id" ]; then
    echo "Kind node image ID $actual_image_id does not match expected $expected_image_id." >&2
    exit 1
  fi
fi

sudo "$kind_path" create cluster --name "$cluster" --image "$kind_node_image" \
  --kubeconfig "$kubeconfig" --wait 180s
sudo chown "$(id -u):$(id -g)" "$kubeconfig"
server_version="$("$kubectl_path" --kubeconfig "$kubeconfig" version --output json \
  | jq -er '.serverVersion.gitVersion')"
if [ "$server_version" != "$kubernetes_version" ]; then
  echo "Kind server version $server_version does not match requested $kubernetes_version." >&2
  exit 1
fi
kind_node_image_id="$(sudo docker image inspect "$kind_node_image" --format '{{.Id}}')"

"$kubectl_path" --kubeconfig "$kubeconfig" apply -f - >/dev/null <<'YAML'
apiVersion: v1
kind: Namespace
metadata:
  name: reasonweave-m5
---
apiVersion: v1
kind: Pod
metadata:
  name: unschedulable
  namespace: reasonweave-m5
spec:
  nodeSelector:
    reasonweave.invalid/never: "true"
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
---
apiVersion: v1
kind: Pod
metadata:
  name: image-pull
  namespace: reasonweave-m5
spec:
  containers:
    - name: app
      image: reasonweave.invalid/does-not-exist:1.0.0
---
apiVersion: v1
kind: Pod
metadata:
  name: missing-config
  namespace: reasonweave-m5
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      envFrom:
        - configMapRef:
            name: missing-config
---
apiVersion: v1
kind: Pod
metadata:
  name: missing-volume
  namespace: reasonweave-m5
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      volumeMounts:
        - name: config
          mountPath: /config
  volumes:
    - name: config
      configMap:
        name: missing-volume-config
---
apiVersion: v1
kind: Pod
metadata:
  name: crash-loop
  namespace: reasonweave-m5
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "exit 7"]
---
apiVersion: v1
kind: Pod
metadata:
  name: oom-killed
  namespace: reasonweave-m5
spec:
  containers:
    - name: app
      image: python:3.12-alpine
      command: ["python", "-c", "chunks=[]\nwhile True: chunks.append(bytearray(1048576))"]
      resources:
        limits:
          memory: 24Mi
        requests:
          memory: 8Mi
---
apiVersion: v1
kind: Pod
metadata:
  name: probe-failure
  namespace: reasonweave-m5
spec:
  containers:
    - name: app
      image: busybox:1.36
      command: ["sh", "-c", "sleep 3600"]
      livenessProbe:
        exec:
          command: ["sh", "-c", "exit 1"]
        initialDelaySeconds: 1
        periodSeconds: 2
        failureThreshold: 1
YAML

collect_until() {
  local pod="$1"
  local predicate="$2"
  local output="$bundle_root/$pod.json"
  local collector_log="$bundle_root/$pod.collector.log"
  local attempt=0
  while [ "$attempt" -lt 90 ]; do
    rm -f -- "$output"
    : > "$collector_log"
    if sudo docker run --rm --network host --user "$(id -u):$(id -g)" \
        -v "$repo_root/tools/evidence-cli:/workspace/evidence-cli:ro" \
        -v "$kubeconfig:/workspace/kubeconfig:ro" \
        -v "$bundle_root:/workspace/output" \
        -v "$kubectl_path:/usr/local/bin/kubectl:ro" \
        "$node_image" node /workspace/evidence-cli/src/cli.mjs kubernetes collect \
        --kubeconfig /workspace/kubeconfig --context "kind-$cluster" \
        --namespace reasonweave-m5 --pod "$pod" --out "/workspace/output/$pod.json" \
        >"$collector_log" 2>&1 \
        && jq -e --arg predicate "$predicate" \
          'any(.evidence_items[].observations[]; .predicate == $predicate and .value == true)' \
          "$output" >/dev/null; then
      rm -f -- "$collector_log"
      printf '%s\n' "$output"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  "$kubectl_path" --kubeconfig "$kubeconfig" -n reasonweave-m5 get pod "$pod" -o wide >&2 || true
  "$kubectl_path" --kubeconfig "$kubeconfig" -n reasonweave-m5 describe pod "$pod" >&2 || true
  if [ -s "$collector_log" ]; then
    echo "Last collector output for Pod $pod:" >&2
    tail -n 120 "$collector_log" >&2
  fi
  echo "Collector did not observe $predicate for Pod $pod." >&2
  return 1
}

accept_bundle() {
  local scenario="$1"
  local expected_hypothesis="$2"
  local query="$3"
  local bundle="$4"
  local subject
  local event_body
  local created
  local event_id
  local imported
  local verified
  local observed_predicates
  local retrieval
  local run
  local run_id
  local graph
  local audit

  subject="$(jq -c '.subject + {id:"subj_primary"}' "$bundle")"
  event_body="$(jq -n --arg title "Kind $scenario" --argjson subject "$subject" '{
    event_ir:{
      schema_version:"eventir/0.1",
      event:{type:"kubernetes_pod_failure",title:$title,domain_pack:"kubernetes-pod-diagnostics/1.0.0"},
      subjects:[$subject],claims:[],evidence:[],observations:[],hypotheses:[],contradictions:[],unknowns:[]
    }
  }')"
  created="$(curl --fail-with-body --silent --show-error \
    -H 'Content-Type: application/json' -H "Idempotency-Key: kind-event-$project-$scenario" \
    --data-binary "$event_body" "$api_root/api/v1/events")"
  event_id="$(jq -er '.data.id' <<<"$created")"
  imported="$(curl --fail-with-body --silent --show-error -H 'Content-Type: application/json' \
    --data-binary "@$bundle" "$api_root/api/v1/events/$event_id/evidence/bundles")"
  assert_json "$scenario" 'bundle import' "$imported" \
    '.data.duplicate == false and (.data.evidence | length) > 0'
  while IFS=$'\t' read -r observation_id observation_version; do
    verified="$(curl --fail-with-body --silent --show-error -X PATCH \
      -H 'Content-Type: application/json' -H "If-Match: $observation_version" \
      --data-binary '{"verification_status":"CONFIRMED"}' \
      "$api_root/api/v1/observations/$observation_id")"
    assert_json "$scenario" "confirm observation $observation_id" "$verified" \
      '.data.verification_status == "CONFIRMED"'
  done < <(jq -r '.data.evidence[].observations[] | [.id, (.version|tostring)] | @tsv' <<<"$imported")
  observed_predicates="$(jq -c '[.data.evidence[].observations[].predicate] | unique' <<<"$imported")"

  retrieval="$(jq -n --arg query "$query" --argjson observed_predicates "$observed_predicates" '{
    query:$query,event_type:"kubernetes_pod_failure",domain_pack_key:"kubernetes-pod-diagnostics/1.0.0",
    intent:"CAUSE_CANDIDATES",observed_predicates:$observed_predicates
  }' | curl --fail-with-body --silent --show-error -H 'Content-Type: application/json' \
    --data-binary @- "$api_root/api/v1/retrieval/debug")"
  assert_json "$scenario" 'hybrid retrieval' "$retrieval" '.data.embedding_provider == "ollama"
    and any(.data.intents[].hits[]; .selected == true)
    and any(.data.intents[].hits[]; .keyword_rank != null)
    and any(.data.intents[].hits[]; .vector_rank != null)
    and any(.data.intents[].hits[]; .selected == true
      and .applicability_reason == "EVENT_AND_CONTEXT_MATCH")'

  run="$(curl --fail-with-body --silent --show-error -X POST \
    -H "Idempotency-Key: kind-investigation-$project-$scenario" \
    "$api_root/api/v1/events/$event_id/investigations")"
  assert_json "$scenario" 'investigation result' "$run" '.data.status == "COMPLETED"
    and .data.evidence_snapshot_schema_version == 2
    and .data.result.hypotheses[0].code == $expected
    and .data.result.hypotheses[0].grounding_status == "GROUNDED"
    and (.data.result.hypotheses[0].citation_ids | length) > 0' \
    --arg expected "$expected_hypothesis"
  run_id="$(jq -er '.data.id' <<<"$run")"
  graph="$(curl --fail-with-body --silent --show-error \
    "$api_root/api/v1/events/$event_id/graph?investigation_id=$run_id")"
  assert_json "$scenario" 'grounding graph' "$graph" \
    'any(.data.edges[]; .type == "GROUNDED_BY" and .score_affecting == false)'
  audit="$(curl --fail-with-body --silent --show-error "$api_root/api/v1/events/$event_id/audit?limit=100")"
  assert_json "$scenario" 'audit trail' "$audit" \
    'any(.data.items[]; .action == "evidence.bundle_imported")
      and any(.data.items[]; .action == "investigation.completed")'
  jq -n --arg scenario "$scenario" --arg event_id "$event_id" --arg run_id "$run_id" \
    --arg hypothesis "$expected_hypothesis" \
    '{scenario:$scenario,event_id:$event_id,investigation_run_id:$run_id,leading_hypothesis:$hypothesis}'
}

unschedulable_bundle="$(collect_until unschedulable pod_unschedulable)"
accept_bundle unschedulable scheduling_constraint 'Pod 无法调度 Unschedulable node selector' "$unschedulable_bundle"
image_bundle="$(collect_until image-pull image_pull_backoff)"
accept_bundle image-pull image_acquisition_failure 'ImagePullBackOff ErrImagePull 镜像拉取失败' "$image_bundle"
config_bundle="$(collect_until missing-config container_config_error)"
accept_bundle missing-config configuration_or_mount_failure 'CreateContainerConfigError 缺少 ConfigMap' "$config_bundle"
volume_bundle="$(collect_until missing-volume volume_mount_failed)"
accept_bundle missing-volume configuration_or_mount_failure 'FailedMount ConfigMap 卷挂载失败' "$volume_bundle"
crash_bundle="$(collect_until crash-loop crash_loop_backoff)"
accept_bundle crash-loop runtime_or_health_failure 'CrashLoopBackOff 容器反复崩溃' "$crash_bundle"
oom_bundle="$(collect_until oom-killed container_oom_killed)"
accept_bundle oom-killed runtime_or_health_failure 'OOMKilled 容器内存不足' "$oom_bundle"
probe_bundle="$(collect_until probe-failure liveness_probe_failed)"
accept_bundle probe-failure runtime_or_health_failure 'liveness probe failed 存活探针失败' "$probe_bundle"

jq -n \
  --arg kind_cluster "$cluster" \
  --arg kubernetes_version "$server_version" \
  --arg node_image "$kind_node_image" \
  --arg node_image_source "$kind_node_image_source" \
  --arg node_image_id "$kind_node_image_id" \
  '{
    kind_cluster: $kind_cluster,
    kubernetes_version: $kubernetes_version,
    node_image: $node_image,
    node_image_source: $node_image_source,
    node_image_id: $node_image_id,
    domain_pack: "kubernetes-pod-diagnostics/1.0.0",
    scenarios: 7,
    paid_calls: 0
  }'
