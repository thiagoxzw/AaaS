#!/usr/bin/env bash
# First measurement of H2 ("the agent is actually useful", docs/01-visao-e-problema.md): breaks demo-api in
# several reproducible ways, asks the agent why the API is down, and writes one markdown row per scenario
# with the answer, the findings the backend computed and the cost. The "Causa correta?" column is for you to
# fill in with Sim / Parcial / Não: there is no target yet (docs/07-plano-do-mvp.md, slice 6).
#
# Requires: the stack running (docker compose up -d), .env in the repository root, curl, jq and docker.
# With LLM_PROVIDER=openai this spends money: every scenario is one agent execution.
#
# Also runs from Git Bash on Windows, where three things broke the first real run (docs/fatias/06-llm-real.md):
# jq prints CRLF, curl.exe receives its arguments in the ANSI code page (so "á" left as one Latin-1 byte and
# the backend rightly refused the JSON), and .env values may hold characters that are not valid shell.
set -euo pipefail

cd "$(dirname "$0")/.."

# jq on Windows ends its lines with CRLF; the \r would end up inside ids and URLs.
jq() { command jq "$@" | tr -d '\r'; }

# Reads KEY from .env without evaluating the file as shell: passwords may contain $, (, & or quotes.
env_value() {
  local value
  value=$(sed -n "s/^$1=//p" .env | tail -n 1 | tr -d '\r')
  case $value in
    \"*\" | \'*\') value=${value:1:${#value}-2} ;;
  esac
  printf '%s' "$value"
}

fail() {
  echo "evaluate-agent: $*" >&2
  exit 1
}

[ -f .env ] || fail ".env not found in $(pwd)"
export ADMIN_EMAIL ADMIN_PASSWORD
ADMIN_EMAIL=$(env_value ADMIN_EMAIL)
ADMIN_PASSWORD=$(env_value ADMIN_PASSWORD)
LLM_PROVIDER=$(env_value LLM_PROVIDER)
LLM_MODEL=$(env_value LLM_MODEL)

API=http://localhost:8080/api/v1
DEMO=http://localhost:8090
CONTAINER=devops-demo-api
QUESTION=${QUESTION:-"Por que minha API está fora do ar?"}
OUT_DIR=evaluations
OUT="$OUT_DIR/evaluation-$(date -u +%Y%m%dT%H%M%SZ).md"
mkdir -p "$OUT_DIR"

auth=()

# METHOD PATH [JSON]: calls the backend and prints the response body. The JSON goes through stdin, never as
# an argument (see the Windows note above). An HTTP error stops the script with the status and the detail.
api() {
  local method=$1 path=$2 body=${3-} response status data=()
  if [ -n "$body" ]; then data=(--data-binary @-); fi
  # ${array[@]+...}: an empty array is "unbound" for set -u on bash 3.2 (macOS).
  response=$(printf '%s' "$body" | curl -sS -X "$method" "$API$path" ${auth[@]+"${auth[@]}"} \
    -H 'Content-Type: application/json' ${data[@]+"${data[@]}"} -w '\n%{http_code}') \
    || fail "$method $path: the backend did not answer"
  status=${response##*$'\n'}
  response=${response%$'\n'*}
  if [ "$status" -ge 400 ]; then
    fail "$method $path → HTTP $status: $(jq -r '.detail // .title // empty' <<<"$response" 2>/dev/null || true)"
  fi
  printf '%s' "$response"
}

token=$(api POST /auth/login "$(jq -n '{email: env.ADMIN_EMAIL, password: env.ADMIN_PASSWORD}')" | jq -r .accessToken)
auth=(-H "Authorization: Bearer $token")

environment=$(api POST /environments "$(jq -n --arg n "eval-$(date +%s)" \
  '{name: $n, type: "DOCKER", tier: "DEV", autonomyLevel: "ASSISTED", connectionRef: "local"}')" | jq -r .id)
api POST "/environments/$environment/services" "$(jq -n --arg c "$CONTAINER" \
  '{name: "demo-api", containerName: $c, description: "Demo API"}')" >/dev/null

wait_for() { # docker inspect format, expected value
  for _ in $(seq 1 60); do
    [ "$(docker inspect -f "$1" "$CONTAINER" | tr -d '\r')" = "$2" ] && return 0
    sleep 2
  done
  fail "timeout waiting for $1 = $2"
}

healthy_again() {
  docker start "$CONTAINER" >/dev/null
  wait_for '{{.State.Health.Status}}' healthy
  curl -sSf -o /dev/null -X POST "$DEMO/chaos/recover" || fail "demo-api did not accept /chaos/recover"
}

ask() { # prints one markdown row
  local scenario=$1 expected=$2 conversation execution view findings
  conversation=$(api POST /conversations "$(jq -n --arg e "$environment" --arg t "$scenario" \
    '{environmentId: $e, title: $t}')" | jq -r .id)
  # The question is read by jq from stdin, so no argument carries non-ASCII text on Windows either.
  execution=$(api POST "/conversations/$conversation/messages" \
    "$(printf '%s' "$QUESTION" | jq -Rs '{content: .}')" | jq -r .executionId)
  for _ in $(seq 1 120); do
    view=$(api GET "/executions/$execution")
    case $(jq -r .status <<<"$view") in QUEUED|RUNNING) sleep 1 ;; *) break ;; esac
  done
  findings=$(docker compose exec -T postgres psql -U devops_agent -d devops_agent -At -c \
    "SELECT string_agg(f->>'code', ', ') FROM tool_execution t, jsonb_array_elements(t.output->'findings') f
     WHERE t.agent_execution_id = '$execution'" | tr -d '\r')
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
  curl -sSf -o /dev/null -X POST "$DEMO/chaos/unhealthy"
  wait_for '{{.State.Health.Status}}' unhealthy
  ask "unhealthy" "healthcheck falhando (chaos/unhealthy)"

  curl -s -o /dev/null -X POST "$DEMO/chaos/crash?code=42" || true
  wait_for '{{.State.Status}}' exited
  ask "crash" "processo saiu com código 42"

  healthy_again
  curl -s -o /dev/null -X POST "$DEMO/chaos/oom" || true
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
echo "Resultado salvo em $OUT. Preencha a coluna \"Causa correta?\" com Sim, Parcial ou Não."
