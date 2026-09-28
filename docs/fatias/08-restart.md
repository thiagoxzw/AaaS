# Fatia 8 — Restart + verificação

> Status: **implementada** (2026-09-28). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 8.

## Objetivo

Provar o fluxo completo de ponta a ponta: diagnóstico → proposta → aprovação → ação → verificação →
auditoria, com a primeira ferramenta de produção que muda alguma coisa: `restartContainer`
([documento 05 §8.5](../05-contratos-das-ferramentas.md#85-restartcontainer)). E responder, pela API, às
perguntas do roteiro do [documento 01 §5](../01-visao-e-problema.md#5-critério-de-sucesso-do-mvp-roteiro-da-demo):
**quem** mandou reiniciar, **quando**, **por quê** e **qual foi o resultado** (RF-46, H3).

**Fica de fora:** `start`/`stop` como ferramentas (V1), quatro olhos (V5), qualquer mudança no prompt
(`agent-system-v2` continua) e a medição de H2 com restart (H2 continua medindo só o diagnóstico).

## Fluxo

```
getContainerStatus / getContainerLogs ─► achados determinísticos (evidências da aprovação)
modelo propõe restartContainer {service, reason} ─► HIGH_RISK ─► approval PENDING
   └─ reason = agent_justification (não confiável), e está dentro do hash dos argumentos
humano aprova ─► retomada (fatia 7) ─► RestartContainerTool:
   1. inspect antes (stateBefore)        ── falhou? FAILED RUNTIME_UNAVAILABLE: nada foi enviado
   2. POST /containers/{nome}/restart?t=10 ── caiu depois do pedido? OUTCOME_UNKNOWN, sem retentativa
   3. inspect a cada 1 s, por até 60 s:
        StartedAt não mudou ─► continua esperando (não é restart concluído)
        HEALTHY / RUNNING sem healthcheck estável por 5 s / UNHEALTHY / NOT_RUNNING / VERIFICATION_TIMEOUT
   └─ SUCCEEDED + verification; se não for HEALTHY nem RUNNING_NO_HEALTHCHECK: achado HIGH RESTART_UNVERIFIED
GET /tool-executions/{id} ─► quem pediu, o que foi observado, por quê, quem aprovou, quando, resultado
```

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | `restartContainer`: `HIGH_RISK`, `TOOL_OPERATE`, entrada `service` + `reason` (10–500 caracteres), timeout de 90 s, sem retentativa, `impactDescription` fixo na definição |
| 2 | O `reason` vira a justificativa da aprovação (`agentClaims`, não confiável). Como é argumento, está dentro do hash: trocar o motivo depois da proposta é `ARGUMENTS_MISMATCH` |
| 3 | A verificação fica dentro da ferramenta: estado anterior, restart com parada graciosa de 10 s, consulta a cada 1 s por até 60 s. **Só conta como reiniciado se o `StartedAt` mudou**. Estados: `HEALTHY`, `RUNNING_NO_HEALTHCHECK` (depois de 5 s estável), `UNHEALTHY`, `NOT_RUNNING`, `VERIFICATION_TIMEOUT` |
| 4 | Verificação ruim é `SUCCEEDED` + achado HIGH `RESTART_UNVERIFIED`, **não** `FAILED`: "o runtime aceitou o restart" e "o serviço voltou saudável" são informações distintas |
| 5 | *Read timeout* próprio para o restart; conexão que cai depois do pedido é `OUTCOME_UNKNOWN` |
| 6 | Proxy com `ALLOW_RESTARTS=1` (e `POST=0`); o script de verificação do compose passa a usar uma lista permitida; o `RealDockerIT` prova que o restart passa e que `start`/`create`/`exec` continuam `403`. `stop`/`kill` passam também: risco residual documentado, não um requisito novo |
| 7 | A auditoria ganha os filtros `toolName`, `agentExecutionId` e `toolExecutionId` (RF-45) |
| 8 | `GET /api/v1/tool-executions/{id}`: a explicação de uma ação (RF-46, H3), com `EXECUTION_READ` |
| 9 | Demonstração com o roteiro `demo-fix` do provedor `scripted`; o prompt continua `agent-system-v2` |
| 10 | `scripts/evaluate-agent.sh` registra um restart proposto e cancela a execução: a avaliação nunca aprova nada |

## Estrutura

```
tool/builtin/        RestartContainerTool (+ Input, Output, Verification), RestartProperties
tool/api/            ToolDefinition.justificationParameter: qual argumento vira a justificativa da aprovação
tool/policy/         PolicyDecision.argument(nome)
tool/execution/      ToolExecutionJournal: a justificativa vem do argumento declarado (ou do rationale)
integration/docker/  DockerEngineContainerRuntime: cliente próprio para o restart, com read timeout de 60 s
audit/               filtros toolName / agentExecutionId / toolExecutionId na consulta e no endpoint
agent/               ActionExplanation + ActionExplanations, api/ToolExecutionController,
                     ExecutionView.Action.toolExecutionId
llm-scripts/         demo-fix.json (status → logs → restartContainer → resposta com o resultado)
docker-compose.yml   ALLOW_RESTARTS: "1"
scripts/             check-compose-docker-socket.sh (lista permitida), evaluate-agent.sh (cancela propostas)
```

**Por que `justificationParameter` na definição, e não um `if (tool == restartContainer)`:** o módulo `tool`
continua sem saber qual ferramenta existe. A definição declara qual argumento (do tipo `String`) é o motivo, o
validador do registro confere isso na inicialização, e o `ToolExecutionJournal` usa esse argumento como
justificativa. Sem ele, a justificativa continua sendo o `rationale` do modelo, como na fatia 7.

## Fatos verificados no proxy real

Medidos com `linuxserver/socket-proxy` 3.4.5 e Docker 29.3.1, com `CONTAINERS=1`, `ALLOW_RESTARTS=1` e
`POST=0`; os pontos marcados com ✅ ficam provados no `RealDockerIT` a cada execução do CI.

| Operação | Resultado | Observação |
|---|---|---|
| `POST /containers/{nome}/restart?t=5` num container rodando | `204` ✅ | Parada graciosa (SIGTERM). O `StartedAt` muda; o `RestartCount` **não** muda (ele conta só os restarts da política do Docker) |
| `restart` num container parado (`exited`) | `204` ✅ | O container volta a rodar |
| O tempo da resposta do `restart` | — | A Docker API só responde quando o container já voltou. Com o *read timeout* normal (8 s), todo restart com parada graciosa viraria um falso `OUTCOME_UNKNOWN`; daí o cliente próprio (decisão 5) |
| `stop`, `kill` | `204` ✅ | **Passam**: o `ALLOW_RESTARTS` do proxy libera os três. Risco residual (TM-B6-04) |
| `start`, `create`, `exec`, `DELETE`, `pause`, `update` | `403` ✅ | Continuam bloqueados |

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| Nada roda sem aprovação | `HIGH_RISK` → `REQUIRE_APPROVAL` (fatia 7) | `RestartContainerIT.anApprovedRestart_restartsOnce_isVerified_andCanBeExplained` (zero restarts antes da decisão), `aRejectedRestart_neverReachesTheRuntime` |
| `OBSERVE_ONLY` nem chega a pedir | Política da ADR-0008 | `inObserveOnly_theRestartIsDenied_withoutAnApproval` |
| O motivo que o aprovador lê é o argumento aprovado | `justificationParameter("reason")` | `anApprovedRestart…`: `agentClaims.justification` = `arguments.reason`, com `trusted: false` |
| **`OUTCOME_UNKNOWN` = "não sabemos se o restart aconteceu"**, e não "o restart falhou" | Exceção do runtime depois do pedido → o executor grava `OUTCOME_UNKNOWN`; `retryable(false)` | `theConnectionDroppingAfterTheRequest_isOutcomeUnknown_notFailed_andIsNeverRetried`: status `OUTCOME_UNKNOWN` (e não `FAILED`), **1** pedido de restart, e o modelo recebe só `OUTCOME_UNKNOWN` com "may have reached" |
| Falha **antes** do pedido não é `OUTCOME_UNKNOWN` | O `inspect` inicial falhou: a ferramenta devolve `FAILED RUNTIME_UNAVAILABLE` ("the restart was not sent") | `RestartContainerToolTest.anUnreachableRuntimeBeforeTheRestart_isAFailure_andNothingIsSent` |
| `StartedAt` que não mudou não é restart concluído | `restarted()` compara o `StartedAt` com o anterior | `RestartContainerToolTest.whenStartedAtDoesNotMove_theRestartIsNotCountedAsDone` (`restartObserved: false`, `VERIFICATION_TIMEOUT`, "no new start of the container was observed") |
| Verificação `UNHEALTHY` é `SUCCEEDED` + `RESTART_UNVERIFIED` | Achado HIGH com a evidência | `RestartContainerIT.aRestartThatComesBackUnhealthy_succeeds_withTheRestartUnverifiedFinding` e `RestartContainerToolTest.anUnhealthyContainer_isASuccessWithAHighFinding` |
| Sem healthcheck, "rodando" precisa ser estável | 5 s rodando, contados a partir da observação do próprio backend | `RestartContainerToolTest.withoutAHealthcheck_theContainerMustStayRunningForTheStableTime` |
| Proxy real | `ALLOW_RESTARTS=1`, `POST=0` | `RealDockerIT.restartContainer_restartsARunningAndAStoppedContainer_andVerifiesWhatCameBack` (com healthcheck → `HEALTHY`; parado → `RUNNING_NO_HEALTHCHECK`), `theProxyRefusesEverythingBeyondReadingContainersAndLogsAndRestarting`, `stopAndKill_alsoPassTheProxy_residualRiskOfAllowRestarts` |
| Configuração do proxy não cresce sem querer | `check-compose-docker-socket.sh`: qualquer variável `=1` fora da lista permitida falha | `RealDockerIT` (checagem do compose) e o job do CI |
| Auditoria por recurso (RF-45) | Filtros novos na consulta | `anApprovedRestart…`: `?resourceType=SERVICE&resourceId=…&toolName=restartContainer` e `?agentExecutionId=…` |
| Explicação de uma ação (RF-46, H3) | `ActionExplanations`, sempre na organização de quem pergunta | `anApprovedRestart…` e `theExplanation_isOnlyForTheCallersOrganization_andNeedsExecutionRead` (outra organização `404`, sem token `401`) |

## Demonstração no compose

Executada em 2026-09-28 no compose, com o provedor `scripted` (roteiro `demo-fix`), a imagem do backend desta
branch e o proxy com `ALLOW_RESTARTS=1`. Os passos seguem o roteiro do documento 01 §5. Os IDs estão
encurtados. O e-mail do administrador aparece como `<ADMIN_EMAIL>`. Como não há endpoint de cadastro de
usuários, o próprio administrador aprovou o pedido: a autoaprovação é aceita no MVP (TM-B7-05).

**1–4.** Com o stack no ar, fiz login, criei o ambiente `local` (`ASSISTED`) com o serviço `demo-api` e
quebrei o serviço com `POST :8090/chaos/unhealthy`:

```
before: running unhealthy StartedAt=2026-09-28T17:49:12.552465278Z
```

**5–7.** Perguntei *"Por que minha API está fora do ar? Se precisar, reinicie."*. A execução pausou, e a
aprovação mostra, separados, o que o sistema afirma e o que o agente diz:

```json
{
  "status": "WAITING_APPROVAL",
  "actions": [
    {"tool": "getContainerStatus", "target": "demo-api", "risk": "READ_ONLY", "status": "SUCCEEDED"},
    {"tool": "getContainerLogs",   "target": "demo-api", "risk": "READ_ONLY", "status": "SUCCEEDED"},
    {"tool": "restartContainer",   "target": "demo-api", "risk": "HIGH_RISK", "status": "WAITING_APPROVAL"}
  ]
}
```

```json
{
  "status": "PENDING",
  "action": {"tool": "restartContainer", "target": "demo-api",
             "arguments": {"reason": "The user asked to fix demo-api; the status and logs were checked first (scripted demo).",
                           "service": "demo-api"}},
  "system": {
    "riskLevel": "HIGH_RISK",
    "impact": "Restarting stops the container and starts it again. In-flight requests will fail and the service will be unavailable for a few seconds or more.",
    "evidence": [{"code": "UNHEALTHY", "severity": "HIGH", "source": "getContainerStatus"},
                 {"code": "RECENTLY_STARTED", "severity": "INFO", "source": "getContainerStatus"}]
  },
  "agentClaims": {"justification": "The user asked to fix demo-api; the status and logs were checked first (scripted demo).",
                  "trusted": false}
}
```

**8.** `POST /approvals/{id}/decision` com `APPROVE` → `200`, `APPROVED`.

**9.** O backend executou a chamada gravada. O proxy registrou um único pedido:

```
"POST /v1.44/containers/devops-demo-api/restart?t=10 HTTP/1.1"  204
after: running healthy StartedAt=2026-09-28T17:50:11.772251435Z
```

A execução terminou `COMPLETED`, com `restartContainer → SUCCEEDED` em 6,5 s. Esse tempo inclui a espera pelo
healthcheck do `demo-api`.

**10.** A explicação da ação, `GET /api/v1/tool-executions/{id}`, com o trecho que responde **quem**,
**quando**, **por quê** e **qual foi o resultado**:

```json
{
  "request": {"requestedBy": "01a0e922-…", "question": "Por que minha API está fora do ar? Se precisar, reinicie.",
              "askedAt": "2026-09-28T17:50:10.224460Z"},
  "action": {"tool": "restartContainer", "target": "demo-api", "risk": "HIGH_RISK"},
  "observations": [
    {"seq": 1, "tool": "getContainerStatus", "status": "SUCCEEDED", "findings": ["UNHEALTHY", "RECENTLY_STARTED"]},
    {"seq": 2, "tool": "getContainerLogs",   "status": "SUCCEEDED", "findings": []}
  ],
  "agentClaims": {"rationale": "I propose restarting demo-api. It needs a human approval before it runs.", "trusted": false},
  "approval": {"status": "APPROVED", "decision": {"decidedBy": "01a0e922-…", "decidedAt": "2026-09-28T17:50:11.478974Z"}},
  "result": {
    "status": "SUCCEEDED",
    "output": {"data": {"service": "demo-api", "stateBefore": "RUNNING", "stateAfter": "RUNNING", "healthAfter": "HEALTHY",
                        "restartedAt": "2026-09-28T17:50:11.772251435Z", "restartObserved": true, "verification": "HEALTHY"},
               "findings": []},
    "durationMs": 6464
  },
  "audit": [
    {"occurredAt": "2026-09-28T17:50:10.650726Z", "action": "TOOL_CALL_AWAITING_APPROVAL", "actorType": "AGENT", "actorLabel": "agent"},
    {"occurredAt": "2026-09-28T17:50:10.654566Z", "action": "APPROVAL_REQUESTED",          "actorType": "AGENT", "actorLabel": "agent"},
    {"occurredAt": "2026-09-28T17:50:11.479143Z", "action": "APPROVAL_GRANTED",            "actorType": "USER",  "actorLabel": "<ADMIN_EMAIL>"},
    {"occurredAt": "2026-09-28T17:50:11.529433Z", "action": "TOOL_EXECUTION_STARTED",      "actorType": "AGENT", "actorLabel": "agent"},
    {"occurredAt": "2026-09-28T17:50:17.993838Z", "action": "TOOL_EXECUTION_SUCCEEDED",    "actorType": "AGENT", "actorLabel": "agent"}
  ]
}
```

E a pergunta "quem reiniciou o `demo-api`?" pela auditoria do serviço,
`GET /audit-events?resourceType=SERVICE&resourceId=…&toolName=restartContainer`, devolveu 3 eventos
(`TOOL_CALL_AWAITING_APPROVAL`, `TOOL_EXECUTION_STARTED` e `TOOL_EXECUTION_SUCCEEDED`), todos com o
`onBehalfOfUserId` de quem pediu.

**Resposta do agente.** No `scripted` ela é um modelo de texto com o resultado da ferramenta, sem nenhum modelo
de linguagem envolvido: `Restart result (scripted answer, no model involved): {"status":"SUCCEEDED", …}`.

### `evaluate-agent.sh` com uma pergunta que pede restart

Rodei `QUESTION="Por que minha API está fora do ar? Se precisar, reinicie." scripts/evaluate-agent.sh` no
mesmo stack. Nos 5 cenários, a linha registrou a proposta e o script cancelou a execução:

```
| unhealthy | … | WAITING_APPROVAL (proposta registrada e cancelada pela avaliação) | getContainerStatus→SUCCEEDED, getContainerLogs→SUCCEEDED, restartContainer→WAITING_APPROVAL | UNHEALTHY, RECENTLY_STARTED | … |
| crash     | … | WAITING_APPROVAL (proposta registrada e cancelada pela avaliação) | … restartContainer→WAITING_APPROVAL | EXITED_WITH_ERROR | … |
```

No banco, as 5 execuções, as 5 aprovações e as 5 chamadas `restartContainer` ficaram `CANCELLED`, e nenhum
restart foi enviado.

### Com a OpenAI (na sua máquina)

A demonstração com o modelo real é o mesmo roteiro, com `LLM_PROVIDER=openai` no `.env`. **Não force o
modelo a propor o restart.** O prompt `agent-system-v2` não mudou, e é legítimo o modelo só diagnosticar e
sugerir. Se ele propuser, a aprovação aparece exatamente como acima. Se não propuser, isso também é um resultado.
Passos, em Git Bash, sem colar a saída de comandos que mostrem o `.env` ou a chave:

1. `docker compose build && docker compose up -d`;
2. `curl -X POST localhost:8090/chaos/unhealthy`;
3. login, `POST /conversations` e `POST /conversations/{id}/messages` com
   *"Por que minha API está fora do ar? Se precisar, reinicie."* (como no README);
4. `GET /executions/{id}`: se estiver `WAITING_APPROVAL`, `GET /approvals/{approvalId}` e
   `POST /approvals/{approvalId}/decision` com `{"decision":"APPROVE"}`;
5. `GET /executions/{id}` até `COMPLETED`, e depois `GET /tool-executions/{toolExecutionId}`.

## Testes

`./mvnw verify`: **329 testes** (178 unitários e 149 de integração no backend, e 2 no `demo-api`), 0 falhas,
SpotBugs sem achados. Os 149 de integração também passaram em ordem alfabética reversa
(`-Dfailsafe.runOrder=reversealphabetical`). Na fatia 7, a ordem de execução escondeu uma dependência entre testes
que compartilham o `FakeContainerRuntime`, e aqui cada teste confere só o seu próprio container.

| Teste | O que prova |
|---|---|
| `RestartContainerToolTest` (9) | A máquina de estados da verificação com o fake: `HEALTHY`; sem healthcheck, só depois do tempo estável; `UNHEALTHY` = sucesso + HIGH; saiu de novo = `NOT_RUNNING` com `healthAfter: NOT_APPLICABLE`; nunca assenta = `VERIFICATION_TIMEOUT`; **`StartedAt` que não mudou não é restart concluído**; runtime fora antes do pedido = `FAILED` sem restart; falha da própria chamada de restart vira exceção (e o executor grava `OUTCOME_UNKNOWN`), com **1** pedido; e a definição (`HIGH_RISK`, sem retentativa, `justificationParameter`) |
| `RestartContainerIT` (6) | Ponta a ponta pela API HTTP com o `demo-fix` + fake + aprovação: o fluxo completo com a explicação e a auditoria; **`OUTCOME_UNKNOWN` quando a conexão cai depois do pedido, afirmado como "não sabemos se o restart foi efetivado" e explicitamente diferente de `FAILED`**, sem retentativa e com o modelo recebendo só `OUTCOME_UNKNOWN`; `UNHEALTHY` depois do restart = `SUCCEEDED` + `RESTART_UNVERIFIED`; rejeitado = zero restarts; `OBSERVE_ONLY` = `DENIED` sem aprovação; explicação `404` para outra organização e `401` sem token |
| `RealDockerIT` (14; 2 novos e 1 renomeado) | Com o proxy real: restart de um container com healthcheck (→ `HEALTHY`) e de um parado (→ `RUNNING_NO_HEALTHCHECK`), com o `/restart` no log do proxy; `stop` e `kill` passam (risco residual registrado); `start`, `create`, `exec`, `DELETE`, `pause` e `update` continuam `403` |
| `ToolRegistryTest.justificationParameterThatIsNotAStringComponent_preventsStartup`, `ArchitectureTest` | Um `justificationParameter` que não é um componente `String` da entrada impede a inicialização, e as fronteiras de módulo continuam as mesmas |

## Divergências e achados

1. **`restartObserved` na saída.** O documento 05 §8.5 lista `service`, `stateBefore`, `stateAfter`,
   `healthAfter`, `restartedAt` e `verification`. Acrescentei `restartObserved`: quando o runtime aceita o
   restart, mas o `StartedAt` nunca muda, a verificação termina em `VERIFICATION_TIMEOUT` com
   `restartObserved: false` e `restartedAt: null`. Sem esse campo, esse caso seria indistinguível de "reiniciou
   e demorou a ficar saudável". Não criei um sexto estado de verificação.
2. **`inspect` inicial que falha → `FAILED`, e não `OUTCOME_UNKNOWN`.** Se o runtime não responde antes do
   pedido, sabemos que o restart não foi enviado; a ferramenta devolve `RUNTIME_UNAVAILABLE` com essa frase.
   `OUTCOME_UNKNOWN` fica reservado para quando o pedido pode ter chegado.
3. **`ExecutionView.Action` ganhou `toolExecutionId`.** Sem ele, um cliente não tem como chegar ao
   `GET /tool-executions/{id}` a partir da execução. É um campo novo, sem mudar nenhum outro.
4. **A janela de verificação é limitada pelo timeout da ferramenta.** Ela termina no que vier primeiro: 60 s
   ou 2 s antes do prazo de 90 s da chamada, para a ferramenta sempre responder antes de o executor desistir
   dela (o que viraria `OUTCOME_UNKNOWN` para um restart que aconteceu).
5. **Leituras que falham durante a verificação são ignoradas**, e a consulta continua até a janela acabar. O
   container está reiniciando, e um erro passageiro não diz nada sobre o resultado.
6. **O `demo-fix` só entra quando a pergunta pede para consertar ou reiniciar** (`reinici`, `restart`,
   `consert`, `fix`). A pergunta padrão do `evaluate-agent.sh` ("Por que minha API está fora do ar?") continua
   no roteiro de diagnóstico, como na fatia 5.
