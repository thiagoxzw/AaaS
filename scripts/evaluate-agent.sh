#!/usr/bin/env bash
# First measurement of H2 ("the agent is actually useful", docs/01-visao-e-problema.md): breaks demo-api in
# several reproducible ways, asks the agent why the API is down, and writes one markdown row per scenario
# with the answer, the findings the backend computed and the cost. The "Causa correta?" column is for you to
# fill in: there is no target yet (docs/07-plano-do-mvp.md, slice 6).
#
# Requires: the stack running (docker compose up -d), .env in the repository root, curl, jq and docker.
# With LLM_PROVIDER=openai this spends money: every scenario is one agent execution.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
. ./.env
set +a

API=http://localhost:8080/api/v1
DEMO=http://localhost:8090
CONTAINER=devops-demo-api
QUESTION=${QUESTION:-"Por que minha API está fora do ar?"}
OUT_DIR=evaluations
OUT="$OUT_DIR/evaluation-$(date -u +%Y%m%dT%H%M%SZ).md"
mkdir -p "$OUT_DIR"

token=$(curl -sf -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" '{email: $e, password: $p}')" | jq -r .accessToken)
auth=(-H "Authorization: Bearer $token")

environment=$(curl -sf -X POST "$API/environments" "${auth[@]}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"eval-$(date +%s)\",\"type\":\"DOCKER\",\"tier\":\"DEV\",\"autonomyLevel\":\"ASSISTED\",\"connectionRef\":\"local\"}" \
  | jq -r .id)
curl -sf -o /dev/null -X POST "$API/environments/$environment/services" "${auth[@]}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"demo-api\",\"containerName\":\"$CONTAINER\",\"description\":\"Demo API\"}"

wait_for() { # docker inspect format, expected value
  for _ in $(seq 1 60); do
    [ "$(docker inspect -f "$1" "$CONTAINER")" = "$2" ] && return 0
    sleep 2
  done
  echo "timeout waiting for $1 = $2" >&2
  return 1
}

healthy_again() {
  docker start "$CONTAINER" >/dev/null
  wait_for '{{.State.Health.Status}}' healthy
  curl -sf -o /dev/null -X POST "$DEMO/chaos/recover"
}

ask() { # prints one markdown row
  local scenario=$1 expected=$2 conversation execution view findings
  conversation=$(curl -sf -X POST "$API/conversations" "${auth[@]}" -H 'Content-Type: application/json' \
    -d "{\"environmentId\":\"$environment\",\"title\":\"$scenario\"}" | jq -r .id)
  execution=$(curl -sf -X POST "$API/conversations/$conversation/messages" "${auth[@]}" \
    -H 'Content-Type: application/json' -d "$(jq -n --arg c "$QUESTION" '{content: $c}')" | jq -r .executionId)
  for _ in $(seq 1 120); do
    view=$(curl -sf "$API/executions/$execution" "${auth[@]}")
    case $(jq -r .status <<<"$view") in QUEUED|RUNNING) sleep 1 ;; *) break ;; esac
  done
  findings=$(docker compose exec -T postgres psql -U devops_agent -d devops_agent -At -c \
    "SELECT string_agg(f->>'code', ', ') FROM tool_execution t, jsonb_array_elements(t.output->'findings') f
     WHERE t.agent_execution_id = '$execution'")
  jq -r --arg s "$scenario" --arg e "$expected" --arg f "${findings:-—}" '
    "| \($s) | \($e) | \(.status)\(if .statusReason then " (\(.statusReason))" else "" end) | " +
    "\([.actions[] | "\(.tool)→\(.status)"] | join(", ")) | \($f) | " +
    "\((.answer.text // "—") | gsub("\n"; " ") | gsub("\\|"; "/")) | \(.usage.estimatedCostUsd) |  |"' <<<"$view"
}

{
  echo "# Avaliação do agente ($(date -u +%Y-%m-%dT%H:%MZ))"
  echo
  echo "Provedor: \`${LLM_PROVIDER:-scripted}\` · modelo: \`${LLM_MODEL:-scripted-v1}\` · pergunta: \"$QUESTION\""
  echo
  echo "| Cenário | Causa real | Execução | Ações | Achados do backend | Resposta do agente | Custo (USD) | Causa correta? |"
  echo "|---|---|---|---|---|---|---|---|"

  healthy_again
  curl -sf -o /dev/null -X POST "$DEMO/chaos/unhealthy"
  wait_for '{{.State.Health.Status}}' unhealthy
  ask "unhealthy" "healthcheck falhando (chaos/unhealthy)"

  curl -sf -o /dev/null -X POST "$DEMO/chaos/crash?code=42" || true
  wait_for '{{.State.Status}}' exited
  ask "crash" "processo saiu com código 42"

  healthy_again
  curl -sf -o /dev/null -X POST "$DEMO/chaos/oom" || true
  wait_for '{{.State.Status}}' exited
  ask "oom" "heap esgotado; a JVM saiu com código 3 (não é OOM do kernel)"

  healthy_again
  docker kill "$CONTAINER" >/dev/null
  ask "kill" "morto com SIGKILL (docker kill)"

  healthy_again
  docker stop "$CONTAINER" >/dev/null
  ask "stop" "parado com docker stop (SIGTERM)"

  healthy_again
} | tee "$OUT"

echo
echo "Resultado salvo em $OUT. Preencha a coluna \"Causa correta?\"."
