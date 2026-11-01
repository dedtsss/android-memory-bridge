#!/usr/bin/env bash
set -euo pipefail

root="${GITHUB_WORKSPACE:?}"
work="${RUNNER_TEMP:?}/memory-bakeoff"
artifacts="$root/experiment/synthetic-memory-bakeoff/results"
network="synthetic-memory-bakeoff"
compose_project="synthetic-memos"
mkdir -p "$artifacts"
exec > >(tee "$artifacts/runner.log") 2>&1

cleanup() {
  set +e
  docker compose --env-file "$work/memos/.env" -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" down -v --remove-orphans
  docker rm -f tencent-memorycore >/dev/null 2>&1
  docker rm -f synthetic-ollama >/dev/null 2>&1
  docker network rm "$network" >/dev/null 2>&1
  docker volume rm synthetic-ollama tencent-synthetic-data >/dev/null 2>&1
}
trap cleanup EXIT

mkdir -p "$work" "$artifacts"
printf 'repository=%s\nref=%s\nrunner=%s\n' "$GITHUB_REPOSITORY" "$GITHUB_REF" "$(id -un)"
nproc
free -h
df -h /
test "$(nproc)" -ge 4
test "$(df -Pk / | awk 'NR==2 {print $4}')" -ge 20000000
docker info --format 'docker_cpus={{.NCPU}} docker_memory_bytes={{.MemTotal}}'
python3 "$root/experiment/synthetic-memory-bakeoff/corpus.py" "$artifacts/corpus.json"
python3 -m py_compile "$root/experiment/synthetic-memory-bakeoff/corpus.py" "$root/experiment/synthetic-memory-bakeoff/benchmark.py"

git clone -q --depth 1 --branch v2.0.34 https://github.com/MemTensor/MemOS.git "$work/memos"
git clone -q --depth 1 --branch v2.0.3 https://github.com/Tencent/TencentDB-Agent-Memory.git "$work/tencent"
test "$(git -C "$work/memos" rev-parse HEAD)" = 41bf5c7fa89ee08ebedf3c662638b06fc29aca8d
test "$(git -C "$work/tencent" rev-parse HEAD)" = 084bed45cad6bb7f877455082cee82835d65671a
python3 - "$work/memos/src/memos/mem_os/utils/default_config.py" "$work/memos/src/memos/api/config.py" <<'PY'
import pathlib
import sys

old = '"tokenizer_or_token_counter": "gpt2"'
expected = (1, 3)
for filename, count in zip(sys.argv[1:], expected):
    path = pathlib.Path(filename)
    text = path.read_text()
    if text.count(old) != count:
        raise SystemExit(f"Pinned MemOS tokenizer config changed unexpectedly: {path}")
    path.write_text(text.replace(old, '"tokenizer_or_token_counter": "character"'))
PY
cat > "$artifacts/sources.json" <<'JSON'
{
  "memos": {"repository": "https://github.com/MemTensor/MemOS", "tag": "v2.0.34", "commit": "41bf5c7fa89ee08ebedf3c662638b06fc29aca8d", "stack": "official self-host REST API + Neo4j + Qdrant"},
  "tencentdb_agent_memory": {"repository": "https://github.com/Tencent/TencentDB-Agent-Memory", "tag": "v2.0.3", "commit": "084bed45cad6bb7f877455082cee82835d65671a", "stack": "official MemoryCore standalone + SQLite + FTS5/BM25"},
  "inference": {"runtime": "local Ollama", "chat_model": "qwen2.5:3b", "embedding_model": "nomic-embed-text", "external_provider": false},
  "memos_chunker_config_override": {"upstream_default": "gpt2", "selected": "character", "reason": "official Chonkie built-in tokenizer avoids network downloads; overridden in four REST/default config sites only in ephemeral upstream checkout"},
  "model_provenance": {"tag": "qwen2.5:3b", "publisher": "Alibaba Qwen", "license": "Qwen Research License Agreement; model weights are not redistributed", "source": "https://ollama.com/library/qwen2.5"}
}
JSON

docker network create --internal "$network"
docker pull ollama/ollama:0.40.13
docker run -d --name synthetic-ollama --cpus 2 --memory 5g -v synthetic-ollama:/root/.ollama ollama/ollama:0.40.13
for attempt in $(seq 1 60); do
  if docker exec synthetic-ollama ollama list >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec synthetic-ollama ollama list >/dev/null
docker exec synthetic-ollama ollama pull qwen2.5:3b
docker exec synthetic-ollama ollama pull nomic-embed-text
docker exec synthetic-ollama ollama list > "$artifacts/local-ollama-models.txt"
grep -q '^qwen2.5:3b' "$artifacts/local-ollama-models.txt"
grep -q '^nomic-embed-text' "$artifacts/local-ollama-models.txt"
docker network connect --alias ollama "$network" synthetic-ollama
docker network disconnect bridge synthetic-ollama

neo4j_password="$(openssl rand -hex 24)"
cat > "$work/memos/.env" <<ENV
MOS_CHAT_MODEL_PROVIDER=openai
MOS_CHAT_MODEL=qwen2.5:3b
MOS_MAX_TOKENS=512
OPENAI_API_KEY=local
OPENAI_API_BASE=http://ollama:11434/v1
QWEN_API_KEY=local
QWEN_API_BASE=http://ollama:11434/v1
MEMRADER_MODEL=qwen2.5:3b
MEMRADER_API_KEY=local
MEMRADER_API_BASE=http://ollama:11434/v1
MEMREADER_GENERAL_MODEL=qwen2.5:3b
MOS_EMBEDDER_BACKEND=ollama
MOS_EMBEDDER_MODEL=nomic-embed-text
EMBEDDING_DIMENSION=768
OLLAMA_API_BASE=http://ollama:11434
MOS_RERANKER_BACKEND=cosine_local
NEO4J_PASSWORD=$neo4j_password
ENV
unset neo4j_password

memos_build_status=0
if ! docker compose -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" pull neo4j qdrant || \
   ! docker compose -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" build memos; then
  memos_build_status=1
  printf 'MemOS image build or upstream dependency pull failed\n' > "$artifacts/memos-build-failure.txt"
fi
python3 - "$work/memos/docker/docker-compose.yml" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
lines = path.read_text().splitlines()
output = []
service = None
skip_ports = False
for line in lines:
    if line.startswith("  ") and not line.startswith("    ") and line.rstrip().endswith(":"):
        service = line.strip()[:-1]
        skip_ports = False
    if line.strip() == "- HF_ENDPOINT=https://hf-mirror.com":
        continue
    if service in {"memos", "neo4j", "qdrant"} and line.strip() == "ports:":
        skip_ports = True
        continue
    if skip_ports:
        if line.startswith("      - "):
            continue
        skip_ports = False
    output.append(line)
text = "\n".join(output) + "\n"
if "ports:" in text or "7474:7474" in text or "6333:6333" in text:
    raise SystemExit("Compose isolation patch did not remove all published service ports")
path.write_text(text)
PY
cat > "$work/memos/experiment-compose.yml" <<'YAML'
services:
  memos:
    ports:
      - "127.0.0.1:18000:8000"
    deploy:
      resources:
        limits:
          cpus: "1.5"
          memory: 4G
    networks:
      - memos_network
  neo4j:
    environment:
      NEO4J_AUTH: neo4j/${NEO4J_PASSWORD}
    deploy:
      resources:
        limits:
          cpus: "1"
          memory: 2G
    networks:
      - memos_network
  qdrant:
    deploy:
      resources:
        limits:
          cpus: "0.5"
          memory: 1G
    networks:
      - memos_network
networks:
  memos_network:
    external: true
    name: synthetic-memory-bakeoff
YAML

printf '\nMEMOS_STAGE_BEGIN\n'
memo_status=0
if [[ "$memos_build_status" -eq 0 ]] && docker compose --env-file "$work/memos/.env" -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" up -d; then
  dependencies_ready=0
  qdrant_ip="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' qdrant-docker)"
  neo4j_password="$(sed -n 's/^NEO4J_PASSWORD=//p' "$work/memos/.env")"
  for attempt in $(seq 1 60); do
    neo4j_status="not_ready"
    if docker exec neo4j-docker cypher-shell -u neo4j -p "$neo4j_password" 'RETURN 1' >/dev/null 2>&1; then neo4j_status="bolt_ready"; fi
    qdrant_status="$(curl -sS -o /dev/null -w '%{http_code}' "http://$qdrant_ip:6333/readyz" || true)"
    if [[ "$neo4j_status" == "bolt_ready" && "$qdrant_status" == "200" ]]; then
      dependencies_ready=1
      break
    fi
    sleep 5
  done
  unset neo4j_password
  printf 'neo4j_bolt=%s\nqdrant_readyz_status=%s\n' "$neo4j_status" "$qdrant_status" > "$artifacts/memos-dependency-readiness.txt"
  memos_ready=0
  if [[ "$dependencies_ready" -eq 1 ]]; then
  for attempt in $(seq 1 36); do
    if curl -fsS "http://127.0.0.1:18000/health" > "$artifacts/memos-health.json" 2>/dev/null; then memos_ready=1; break; fi
    sleep 5
  done
  fi
  if [[ "$memos_ready" -eq 1 ]]; then
    [[ "$(docker port memos-api-docker 8000/tcp)" == "127.0.0.1:18000" ]]
    MEMOS_URL="http://127.0.0.1:18000" MEMOS_COMPOSE_FILE="$work/memos/docker/docker-compose.yml" MEMOS_COMPOSE_PROJECT="$compose_project" MEMOS_COMPOSE_ENV_FILE="$work/memos/.env" \
      python3 "$root/experiment/synthetic-memory-bakeoff/benchmark.py" memos "$artifacts/corpus.json" "$artifacts" || memo_status=$?
  else
    memo_status=1
    printf 'MemOS health did not become ready\n' > "$artifacts/memos-failure.txt"
  fi
  docker logs memos-api-docker > "$artifacts/memos-service.log" 2>&1 || true
else
  memo_status=1
  printf 'MemOS Compose startup failed or image build unavailable\n' > "$artifacts/memos-failure.txt"
fi
printf 'MEMOS_BENCHMARK_EXIT=%s\n' "$memo_status" | tee "$artifacts/memos-exit.txt"
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' memos-api-docker neo4j-docker qdrant-docker > "$artifacts/memos-resource-snapshot.txt" 2>&1 || true
docker system df > "$artifacts/memos-docker-disk.txt" 2>&1 || true
docker compose --env-file "$work/memos/.env" -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" down -v --remove-orphans
docker builder prune --all --force
docker image prune --all --force
docker system df > "$artifacts/post-memos-cleanup-disk.txt" 2>&1 || true
printf 'MEMOS_STAGE_END\n'

cat > "$work/tencent/tdai-gateway.standalone.yaml" <<'YAML'
deployMode: standalone
stateBackend: local
server:
  port: 8420
  host: 0.0.0.0
data:
  baseDir: /data/tdai-memory
llm:
  baseUrl: http://ollama:11434/v1
  apiKey: local
  model: qwen2.5:3b
  maxTokens: 2048
  timeoutMs: 300000
memory:
  capture:
    enabled: true
  extraction:
    enabled: true
    enableDedup: true
    maxMemoriesPerSession: 20
  persona:
    triggerEveryN: 1
    maxScenes: 15
    triggerMode: current
  pipeline:
    everyNConversations: 1
    enableWarmup: true
    l1IdleTimeoutSeconds: 2
    l2DelayAfterL1Seconds: 2
    l2MinIntervalSeconds: 2
    l2MaxIntervalSeconds: 5
  recall:
    enabled: true
    maxResults: 20
    scoreThreshold: 0.0
    strategy: hybrid
    timeoutMs: 300000
  storeBackend: sqlite
  embedding:
    provider: none
  bm25:
    enabled: true
    language: zh
upstream:
  supportedAgents:
    - agent_source: synthetic-agent
      protocol: openai-chat
      display_name: Synthetic Benchmark Agent
YAML
gateway_api_key="$(openssl rand -hex 32)"
sed -i '/^server:/a\  apiKey: "${TDAI_GATEWAY_API_KEY}"' "$work/tencent/tdai-gateway.standalone.yaml"
ten_build_status=0
if ! docker build -t synthetic-tencent-memorycore:084bed45 "$work/tencent/MemoryCore"; then
  ten_build_status=1
  printf 'TencentDB MemoryCore image build failed\n' > "$artifacts/tencent-build-failure.txt"
fi
ten_status=0
if [[ "$ten_build_status" -eq 0 ]] && docker run -d --name tencent-memorycore --network "$network" --network-alias tencent-memorycore \
  --cpus 2 --memory 4g \
  -e TDAI_LLM_API_KEY=local -e TDAI_GATEWAY_API_KEY="$gateway_api_key" \
  -p 127.0.0.1:18420:8420 \
  -v "$work/tencent/tdai-gateway.standalone.yaml:/data/config/tdai-gateway.yaml:ro" \
  -v tencent-synthetic-data:/data/tdai-memory \
  synthetic-tencent-memorycore:084bed45; then
  tencent_ready=0
  for attempt in $(seq 1 36); do
    if curl -fsS "http://127.0.0.1:18420/health" > "$artifacts/tencent-health.json" 2>/dev/null; then tencent_ready=1; break; fi
    sleep 5
  done
else
  tencent_ready=0
  if [[ "$ten_build_status" -ne 0 ]]; then ten_status=1; fi
fi
printf '\nTENCENTDB_STAGE_BEGIN\n'
if [[ "$tencent_ready" -eq 1 ]]; then
  [[ "$(docker port tencent-memorycore 8420/tcp)" == "127.0.0.1:18420" ]]
  TENCENTDB_URL="http://127.0.0.1:18420" TENCENTDB_API_KEY="$gateway_api_key" python3 "$root/experiment/synthetic-memory-bakeoff/benchmark.py" tencentdb "$artifacts/corpus.json" "$artifacts" || ten_status=$?
else
  ten_status=1
  printf 'TencentDB MemoryCore image unavailable, did not start, or did not become healthy\n' > "$artifacts/tencent-failure.txt"
fi
printf 'TENCENTDB_BENCHMARK_EXIT=%s\n' "$ten_status" | tee "$artifacts/tencent-exit.txt"
printf 'TENCENTDB_STAGE_END\n'
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' tencent-memorycore > "$artifacts/tencent-resource-snapshot.txt" 2>&1 || true
docker system df > "$artifacts/tencent-docker-disk.txt" 2>&1 || true
docker logs tencent-memorycore > "$artifacts/tencent-service.log" 2>&1 || true
docker compose --env-file "$work/memos/.env" -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" down -v --remove-orphans
docker rm -f tencent-memorycore synthetic-ollama >/dev/null
docker network rm "$network"
unset gateway_api_key
trap - EXIT
if [[ "$memo_status" -eq 0 && "$ten_status" -eq 0 ]]; then
  printf 'RESULT=SUCCESS\n'
else
  printf 'RESULT=PARTIAL memos=%s tencentdb=%s\n' "$memo_status" "$ten_status"
  exit 1
fi
