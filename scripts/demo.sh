#!/usr/bin/env bash
# The canonical demonstration (docs/01-visao-e-problema.md, section 5): break demo-api, ask the agent, see the
# proposal wait for a human, approve it, and follow the restart, its verification and the audit trail, all
# through the HTTP API. Every step prints what the backend recorded, never what the model merely said.
#
# Requires: the stack running (docker compose up -d), .env in the repository root, curl, jq and docker.
# Usage: scripts/demo.sh [--yes]      --yes approves without asking.
# With LLM_PROVIDER=openai this spends money, and the model may diagnose without proposing a restart: that is a
# valid outcome, and the script stops there. It never prints the token nor anything from .env.
#
# Also runs from Git Bash on Windows (see scripts/evaluate-agent.sh for the three things that broke there).
set -euo pipefail

cd "$(dirname "$0")/.."

jq() { command jq "$@" | tr -d '\r'; }

env_value() {
  local value
  value=$(sed -n "s/^$1=//p" .env | tail -n 1 | tr -d '\r')
  case $value in
    \"*\" | \'*\') value=${value:1:${#value}-2} ;;
  esac
  printf '%s' "$value"
}

fail() {
  echo "demo: $*" >&2
  exit 1
}

AUTO_APPROVE=false
[ "${1-}" = "--yes" ] && AUTO_APPROVE=true
[ -f .env ] || fail ".env not found in $(pwd)"
export ADMIN_EMAIL ADMIN_PASSWORD
ADMIN_EMAIL=$(env_value ADMIN_EMAIL)
ADMIN_PASSWORD=$(env_value ADMIN_PASSWORD)

API=http://localhost:8080/api/v1
DEMO=http://localhost:8090
CONTAINER=devops-demo-api
QUESTION=${QUESTION:-"Por que minha API está fora do ar? Se precisar, reinicie."}
auth=()

api() { # METHOD PATH [JSON], the JSON through stdin; an HTTP error stops the demo with its detail
  local method=$1 path=$2 body=${3-} response status data=()
  if [ -n "$body" ]; then data=(--data-binary @-); fi
  response=$(printf '%s' "$body" | curl -sS -X "$method" "$API$path" ${auth[@]+"${auth[@]}"} \
    -H 'Content-Type: application/json' ${data[@]+"${data[@]}"} -w '\n%{http_code}') \
    || fail "$method $path: the backend did not answer (is the stack up? docker compose up -d)"
  status=${response##*$'\n'}
  response=${response%$'\n'*}
  if [ "$status" -ge 400 ]; then
    fail "$method $path → HTTP $status: $(jq -r '.detail // .title // empty' <<<"$response" 2>/dev/null || true)"
  fi
  printf '%s' "$response"
}

step() { printf '\n\033[1m[%s] %s\033[0m\n' "$1" "$2"; }

settled() { # EXECUTION "STATUSES TO WAIT ON": polls until the execution leaves them
  local view
  for _ in $(seq 1 150); do
    view=$(api GET "/executions/$1")
    case " $2 " in *" $(jq -r .status <<<"$view") "*) sleep 1 ;; *) printf '%s' "$view"; return ;; esac
  done
  fail "execution $1 did not settle"
}

health() { docker inspect -f '{{.State.Status}}/{{.State.Health.Status}}' "$CONTAINER" | tr -d '\r'; }

step 1/9 "Login"
token=$(api POST /auth/login "$(jq -n '{email: env.ADMIN_EMAIL, password: env.ADMIN_PASSWORD}')" | jq -r .accessToken)
auth=(-H "Authorization: Bearer $token")
echo "ok (the token is not printed)"

step 2/9 "An environment and its allowlist: the agent sees only \"demo-api\", mapped to $CONTAINER"
environment=$(api POST /environments "$(jq -n --arg n "demo-$(date +%s)" \
  '{name: $n, type: "DOCKER", tier: "DEV", autonomyLevel: "ASSISTED", connectionRef: "local"}')" | jq -r .id)
service=$(api POST "/environments/$environment/services" "$(jq -n --arg c "$CONTAINER" \
  '{name: "demo-api", containerName: $c, description: "Demo API"}')" | jq -r .id)
echo "environment $environment (ASSISTED), service demo-api"

step 3/9 "Break demo-api (its healthcheck starts failing)"
curl -sSf -o /dev/null -X POST "$DEMO/chaos/unhealthy" || fail "demo-api did not accept /chaos/unhealthy"
for _ in $(seq 1 60); do [ "$(health)" = running/unhealthy ] && break; sleep 2; done
echo "docker says: $(health)"

step 4/9 "Ask: \"$QUESTION\""
conversation=$(api POST /conversations "$(jq -n --arg e "$environment" '{environmentId: $e, title: "demo"}')" \
  | jq -r .id)
execution=$(api POST "/conversations/$conversation/messages" "$(printf '%s' "$QUESTION" | jq -Rs '{content: .}')" \
  | jq -r .executionId)
view=$(settled "$execution" "QUEUED RUNNING")
jq -r '"execution: \(.status)", (.actions[] | "  \(.seq). \(.tool) → \(.status)\(if .denialReason then " (\(.denialReason))" else "" end)")' <<<"$view"
if [ "$(jq -r .status <<<"$view")" != WAITING_APPROVAL ]; then
  echo
  echo "The agent did not propose a restart; nothing waits for a human. Its answer:"
  jq -r '.answer.text // "—"' <<<"$view"
  exit 0
fi

step 5/9 "The approval: what the system asserts, apart from what the agent claims"
approval=$(jq -r '.approvals[0].approvalId' <<<"$view")
api GET "/approvals/$approval" | jq '{status, action, system: (.system | {riskLevel, impact,
  evidence: [.evidence[] | "\(.code) (\(.severity), from \(.source))"]}), agentClaims}'

step 6/9 "Decide"
decision=APPROVE
if [ "$AUTO_APPROVE" = false ]; then
  if ! { read -r -p "Approve the restart of demo-api? [y/N] " answer </dev/tty; } 2>/dev/null; then
    echo "no terminal to ask: rejecting (run with --yes to approve)"
    answer=n
  fi
  case $answer in y | Y | s | S) ;; *) decision=REJECT ;; esac
fi
api POST "/approvals/$approval/decision" "$(jq -n --arg d "$decision" '{decision: $d, comment: "scripts/demo.sh"}')" \
  | jq -r '"approval: \(.status) by \(.decision.decidedBy) at \(.decision.decidedAt)"'

step 7/9 "What ran, and what the backend verified"
view=$(settled "$execution" "QUEUED RUNNING WAITING_APPROVAL")
jq -r '"execution: \(.status)", (.actions[] | "  \(.seq). \(.tool) → \(.status)")' <<<"$view"
restart=$(jq -r '[.actions[] | select(.tool == "restartContainer")][0].toolExecutionId' <<<"$view")
explanation=$(api GET "/tool-executions/$restart")
jq '.result | {status, verification: .output.data?, findings: [.output.findings[]?.code]}' <<<"$explanation"
echo "docker says now: $(health)"
if [ "$decision" = REJECT ]; then
  echo "Nothing was restarted. To bring demo-api back: curl -X POST $DEMO/chaos/recover"
fi

step 8/9 "Who asked, who approved, when, why, and the result (GET /tool-executions/{id})"
jq '{asked: .request, observedBefore: [.observations[] | "\(.tool): \(if (.findings | length) == 0 then "no findings" else (.findings | join(", ")) end)"],
  agentClaims, approval: {status: .approval.status, decidedAt: .approval.decision.decidedAt},
  result: .result.status,
  audit: [.audit[] | "\(.occurredAt) \(.action) by \(.actorType) \(.actorLabel)"]}' <<<"$explanation"

step 9/9 "Who restarted demo-api? (audit by resource and tool)"
api GET "/audit-events?resourceType=SERVICE&resourceId=$service&toolName=restartContainer" \
  | jq -r '.items[] | "\(.occurredAt) \(.action) — \(.actorType) on behalf of \(.onBehalfOfUserId)"'
