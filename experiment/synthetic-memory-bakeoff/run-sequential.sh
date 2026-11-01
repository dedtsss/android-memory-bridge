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
  docker compose -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" down -v --remove-orphans
  docker rm -f tencent-memorycore >/dev/null 2>&1
  docker rm -f synthetic-ollama >/dev/null 2>&1
  docker network rm "$network" >/dev/null 2>&1
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
cat > "$artifacts/sources.json" <<'JSON'
{
  "memos": {"repository": "https://github.com/MemTensor/MemOS", "tag": "v2.0.34", "commit": "41bf5c7fa89ee08ebedf3c662638b06fc29aca8d", "stack": "official self-host REST API + Neo4j + Qdrant"},
  "tencentdb_agent_memory": {"repository": "https://github.com/Tencent/TencentDB-Agent-Memory", "tag": "v2.0.3", "commit": "084bed45cad6bb7f877455082cee82835d65671a", "stack": "official MemoryCore standalone + SQLite + FTS5/BM25"},
  "inference": {"runtime": "local Ollama", "chat_model": "qwen3:0.6b", "embedding_model": "nomic-embed-text", "external_provider": false}
}
JSON

docker network create --internal "$network"
docker pull ollama/ollama:0.40.13
docker run -d --name synthetic-ollama --cpus 2 --memory 5g -p 127.0.0.1:11434:11434 -v synthetic-ollama:/root/.ollama ollama/ollama:0.40.13
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then break; fi
  sleep 2
done
curl -fsS http://127.0.0.1:11434/api/tags >/dev/null
docker exec synthetic-ollama ollama pull qwen3:0.6b
docker exec synthetic-ollama ollama pull nomic-embed-text
docker network connect --alias ollama "$network" synthetic-ollama
docker network disconnect bridge synthetic-ollama

neo4j_password="$(openssl rand -hex 24)"
cat > "$work/memos/.env" <<ENV
MOS_CHAT_MODEL_PROVIDER=openai
MOS_CHAT_MODEL=qwen3:0.6b
MOS_MAX_TOKENS=512
OPENAI_API_KEY=local
OPENAI_API_BASE=http://ollama:11434/v1
QWEN_API_KEY=local
QWEN_API_BASE=http://ollama:11434/v1
MEMRADER_MODEL=qwen3:0.6b
MEMRADER_API_KEY=local
MEMRADER_API_BASE=http://ollama:11434/v1
MEMREADER_GENERAL_MODEL=qwen3:0.6b
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
ten_build_status=0
if ! docker build -t synthetic-tencent-memorycore:084bed45 "$work/tencent/MemoryCore"; then
  ten_build_status=1
  printf 'TencentDB MemoryCore image build failed\n' > "$artifacts/tencent-build-failure.txt"
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
    if service in {"neo4j", "qdrant"} and line.strip() == "ports:":
        skip_ports = True
        continue
    if skip_ports:
        if line.startswith("      - "):
            continue
        skip_ports = False
    if service == "memos" and '"8000:8000"' in line:
        line = line.replace('"8000:8000"', '"127.0.0.1:8000:8000"')
    output.append(line)
text = "\n".join(output) + "\n"
if '"127.0.0.1:8000:8000"' not in text or "7474:7474" in text or "6333:6333" in text:
    raise SystemExit("Compose isolation patch did not pass its port checks")
path.write_text(text)
PY
cat > "$work/memos/experiment-compose.yml" <<'YAML'
services:
  memos:
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
if [[ "$memos_build_status" -eq 0 ]] && docker compose -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" up -d; then
  memos_ready=0
  for attempt in $(seq 1 120); do
    if curl -fsS http://127.0.0.1:8000/health > "$artifacts/memos-health.json" 2>/dev/null; then memos_ready=1; break; fi
    sleep 5
  done
  if [[ "$memos_ready" -eq 1 ]]; then
    MEMOS_URL=http://127.0.0.1:8000 MEMOS_COMPOSE_FILE="$work/memos/docker/docker-compose.yml" MEMOS_COMPOSE_PROJECT="$compose_project" \
      python3 "$root/experiment/synthetic-memory-bakeoff/benchmark.py" memos "$artifacts/corpus.json" "$artifacts" || memo_status=$?
  else
    memo_status=1
    printf 'MemOS health did not become ready\n' > "$artifacts/memos-failure.txt"
  fi
else
  memo_status=1
  printf 'MemOS Compose startup failed or image build unavailable\n' > "$artifacts/memos-failure.txt"
fi
printf 'MEMOS_BENCHMARK_EXIT=%s\n' "$memo_status" | tee "$artifacts/memos-exit.txt"
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' memos-api-docker neo4j-docker qdrant-docker > "$artifacts/memos-resource-snapshot.txt" 2>&1 || true
docker system df > "$artifacts/memos-docker-disk.txt" 2>&1 || true
docker compose -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" down -v --remove-orphans
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
  model: qwen3:0.6b
  maxTokens: 512
  timeoutMs: 120000
memory:
  capture:
    enabled: true
  extraction:
    enabled: true
    enableDedup: true
    maxMemoriesPerSession: 20
  persona:
    triggerEveryN: 50
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
    timeoutMs: 120000
  storeBackend: sqlite
  embedding:
    provider: none
  bm25:
    enabled: true
    language: zh
YAML
ten_status=0
if [[ "$ten_build_status" -eq 0 ]] && docker run -d --name tencent-memorycore --network "$network" --network-alias tencent-memorycore \
  --cpus 2 --memory 4g -p 127.0.0.1:8420:8420 \
  -e TDAI_LLM_API_KEY=local \
  -v "$work/tencent/tdai-gateway.standalone.yaml:/data/config/tdai-gateway.yaml:ro" \
  -v tencent-synthetic-data:/data/tdai-memory \
  synthetic-tencent-memorycore:084bed45; then
  tencent_ready=0
  for attempt in $(seq 1 120); do
    if curl -fsS http://127.0.0.1:8420/health > "$artifacts/tencent-health.json" 2>/dev/null; then tencent_ready=1; break; fi
    sleep 5
  done
else
  tencent_ready=0
  if [[ "$ten_build_status" -ne 0 ]]; then ten_status=1; fi
fi
printf '\nTENCENTDB_STAGE_BEGIN\n'
if [[ "$tencent_ready" -eq 1 ]]; then
  TENCENTDB_URL=http://127.0.0.1:8420 python3 "$root/experiment/synthetic-memory-bakeoff/benchmark.py" tencentdb "$artifacts/corpus.json" "$artifacts" || ten_status=$?
else
  ten_status=1
  printf 'TencentDB MemoryCore image unavailable, did not start, or did not become healthy\n' > "$artifacts/tencent-failure.txt"
fi
printf 'TENCENTDB_BENCHMARK_EXIT=%s\n' "$ten_status" | tee "$artifacts/tencent-exit.txt"
printf 'TENCENTDB_STAGE_END\n'
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' tencent-memorycore > "$artifacts/tencent-resource-snapshot.txt" 2>&1 || true
docker system df > "$artifacts/tencent-docker-disk.txt" 2>&1 || true
docker logs tencent-memorycore > "$artifacts/tencent-service.log" 2>&1 || true
docker compose -p "$compose_project" -f "$work/memos/docker/docker-compose.yml" -f "$work/memos/experiment-compose.yml" down -v --remove-orphans
docker rm -f tencent-memorycore synthetic-ollama >/dev/null
docker network rm "$network"
trap - EXIT
if [[ "$memo_status" -eq 0 && "$ten_status" -eq 0 ]]; then
  printf 'RESULT=SUCCESS\n'
else
  printf 'RESULT=PARTIAL memos=%s tencentdb=%s\n' "$memo_status" "$ten_status"
  exit 1
fi
