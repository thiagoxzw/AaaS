# Fatia 7 — Aprovação e retomada

> Status: **implementada** (2026-09-28). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 7.

## Objetivo

Provar que a aprovação humana é uma máquina de estados estruturada, segura contra replay, TOCTOU e concorrência
([ADR-0006](../adr/0006-aprovacao-como-entidade.md)). A pausa em `WAITING_APPROVAL` já existia desde a fatia 4;
esta fatia acrescenta a aprovação, a decisão e a retomada.

**Fica de fora:** quatro olhos (V5), notificações (V2+) e a ferramenta real `restartContainer` (fatia 8).

## Fluxo

```
modelo propõe testRestart ─► política: REQUIRE_APPROVAL ─► tool_execution WAITING_APPROVAL
                                                            + approval PENDING (na mesma transação)
                                                            execução WAITING_APPROVAL
humano: POST /approvals/{id}/decision
  ├─ APPROVE ─► APPROVED + auditoria (mesma transação, sob o lock da aprovação)
  │             └─ depois do commit: retomada ─► confere os hashes ─► reavalia a política
  │                                           ─► executa os argumentos GRAVADOS (ou DENIED)
  └─ REJECT  ─► REJECTED ─► o modelo recebe {"status":"REJECTED"}
prazo vencido (varredura ou decisão atrasada) ─► EXPIRED ─► o modelo recebe {"status":"EXPIRED"}
nenhuma aprovação PENDING na execução ─► WAITING_APPROVAL → RUNNING, e o loop continua
```

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | Módulo novo `approval`; ele avisa o agente por um evento depois do commit (ArchUnit: nada depende de `agent`, e `tool` não depende de `approval`) |
| 2 | Tabela `approval` (V8) conforme o documento 04 §4.10, com `organization_id` e `agent_execution_id`, único por `tool_execution_id`, `CHECK` de autor e horário na decisão |
| 3 | Decisão de uso único, com a auditoria na mesma transação; segunda decisão e decisão atrasada → `409` |
| 4 | A retomada executa só o que foi gravado: hashes comparados e política reavaliada com o estado atual; se a política negar, a aprovação continua `APPROVED` e a chamada vira `DENIED`. O LLM não é consultado |
| 5 | Retomada condicional (`WAITING_APPROVAL → RUNNING`) na execução e em cada chamada |
| 6 | Várias propostas arriscadas na mesma volta: uma aprovação para cada, e a retomada só quando nenhuma estiver pendente |
| 7 | Rejeição e expiração retomam o loop; o modelo recebe só o status. O comentário do aprovador não vai ao provedor |
| 8 | Prazo de 15 min, configurável; varredura a cada 30 s, **idempotente e guiada só pelo estado gravado**: expira o que venceu e retoma o que não tem mais pendência. O endpoint também confere o prazo |
| 9 | API: `GET /approvals?status=…` e `GET /approvals/{id}` com `EXECUTION_READ`; `POST /approvals/{id}/decision` com `APPROVAL_DECIDE`, verificada com o estado atual; resposta no contrato do documento 05 §13 |
| 10 | Autoaprovação permitida no MVP (TM-B7-05); cancelar a execução cancela as aprovações |

**Demonstração:** por teste de integração pela API HTTP, com a ferramenta de teste `testRestart`, sem nenhuma
ferramenta falsa no código de produção. A demonstração no compose, com o `restartContainer` real, fica para a
fatia 8.

## Estrutura

```
approval/            Approval (entidade), ApprovalWorkflow (máquina de estados, expiração, cancelamento),
                     ApprovalQueries + ApprovalView (contrato §13), api/ApprovalController, ApprovalDecided (evento)
tool/execution/      ToolCallAwaitingApproval (evento na transação da chamada), ApprovedCall,
                     ToolExecutor.executeApproved, ToolExecutionJournal.claimApproved
tool/policy/         PolicyEngine.evaluateApproved, DenialReason.ARGUMENTS_MISMATCH
agent/               ExecutionJournal.resume, AgentOrchestrator.resume, ExecutionDispatcher.resume,
                     ApprovalResumption (evento pós-commit + varredura)
db/migration/        V8__create_approval.sql
```

**Como a aprovação nasce com a chamada:** o `ToolExecutionJournal` publica `ToolCallAwaitingApproval` dentro da
transação que grava a chamada como `WAITING_APPROVAL`, e o `ApprovalWorkflow` escuta esse evento de forma
síncrona, na mesma transação (`MANDATORY`). Assim o módulo `tool` não depende de `approval`, e nunca existe uma
chamada esperando sem a sua aprovação.

## Como cada garantia foi implementada

| Garantia | Implementação | Prova (`ApprovalIT`, salvo indicação) |
|---|---|---|
| Aprovação estruturada, não texto (S3/S6) | Só o endpoint decide; o texto do modelo não tem efeito | `s6_theModelSayingItWasApproved_approvesNothing`, e `AgentIT.aRiskyProposal_waitsForApproval_whateverTheModelClaims` |
| Uso único e clique duplo (TM-B7-03) | Decisão sob o *lock* da linha da aprovação; só `PENDING` muda | `anApproval_cannotBeDecidedTwice` |
| Concorrência (S10) | *Lock* da aprovação + retomada condicional da execução e da chamada | `s10_concurrentDecisionsAndResumptions_restartExactlyOnce`: 8 decisões em paralelo (1×`200`, 7×`409`) e 4 retomadas em paralelo → **1** restart |
| TOCTOU nos argumentos (TM-B7-02) | Hash em três pontos: a cópia da aprovação, o hash da chamada e os argumentos gravados vinculados de novo | `storedArgumentsChangedAfterTheProposal_areDenied_withArgumentsMismatch`, `aChangedCallHash_isDenied_withArgumentsMismatch` |
| Contexto que mudou (S11, TM-B7-04) | `PolicyEngine.evaluateApproved`: a cadeia inteira de novo, só com a aprovação satisfeita e o orçamento já contado | `s11_autonomyLoweredWhilePending_theApprovalStands_butTheCallIsDenied`; `PolicyEngineTest.anApprovedCall_skipsOnlyApprovalAndBudget_andIsStillDeniedByTheCurrentState` |
| Expiração (RF-43) | Varredura idempotente + conferência no endpoint | `aLateDecision_findsTheApprovalExpired`, `theSweep_expiresWhatIsDue_once_andResumesTheExecution` (a segunda varredura não faz nada) |
| Quem decide (TM-B7-01) | `APPROVAL_DECIDE` com as permissões recarregadas a cada requisição | `onlyAUserHoldingApprovalDecideNow_canDecide`: operador `403`, aprovador rebaixado depois do login `403`, outra organização `404`, decisão inválida e comentário longo `400` |
| Decisão e auditoria atômicas (TM-B7-07) | A mesma transação | `theDecisionAndItsAuditEvent_areAtomic`: um *trigger* faz a auditoria falhar, e a aprovação continua `PENDING` |
| `system` × `agentClaims` (TM-B7-06) | `ApprovalView`: impacto da definição, evidências = achados determinísticos da execução, justificativa como texto puro com `trusted: false` | `anApprovedCall_runsExactlyAsRecorded_andTheExecutionResumes` (a justificativa com `<b>` volta literal, só em `agentClaims`) |
| Várias aprovações na mesma volta | Retomada só sem nenhuma `PENDING` | `twoRiskyCallsInOneTurn_resumeOnlyWhenBothAreDecided` |
| Cancelamento | As aprovações pendentes viram `CANCELLED` | `cancellingTheExecution_cancelsItsApprovals` |
| Sobrevive a um reinício (RNF-CONF-08) | O estado está no banco; a varredura retoma uma decisão que o processo não chegou a executar | `aWaitingExecution_survivesARestart_andADecisionLeftBehindIsResumedByTheSweep` |
| Autoaprovação (TM-B7-05, aceita) | Sem restrição no MVP | `selfApproval_isAllowedInTheMvp` |
| Módulos | ArchUnit | `ArchitectureTest.tools_do_not_depend_on_approvals`, `nothing_depends_on_the_agent` |

## Demonstração pela API HTTP

Saída real do teste `anApprovedCall_runsExactlyAsRecorded_andTheExecutionResumes` (roteiro
`test-approval-evidence`: o "modelo" consulta o status, depois propõe o restart com uma justificativa que tenta
convencer o aprovador).

**1. A execução para** (`GET /api/v1/executions/{id}`, trecho):

```json
{
  "executionId": "01a0e900-9df8-70d6-b376-4d4d2f813fdd",
  "status": "WAITING_APPROVAL",
  "actions": [
    {
      "tool": "testStatus",
      "status": "SUCCEEDED"
    },
    {
      "tool": "testRestart",
      "status": "WAITING_APPROVAL"
    }
  ],
  "approvals": [
    {
      "approvalId": "01a0e900-9efe-760f-a221-13bf39f351cd",
      "toolExecutionId": "01a0e900-9efc-77a6-955a-7e677613c3bb",
      "status": "PENDING",
      "expiresAt": "2026-09-28T17:27:07.166072Z"
    }
  ]
}
```

**2. O aprovador lê a aprovação** (`GET /api/v1/approvals/{id}`, completo):

```json
{
  "approvalId" : "01a0e900-9efe-760f-a221-13bf39f351cd",
  "status" : "PENDING",
  "createdAt" : "2026-09-28T17:12:07.166072Z",
  "expiresAt" : "2026-09-28T17:27:07.166072Z",
  "agentExecutionId" : "01a0e900-9df8-70d6-b376-4d4d2f813fdd",
  "toolExecutionId" : "01a0e900-9efc-77a6-955a-7e677613c3bb",
  "action" : {
    "tool" : "testRestart",
    "target" : "demo-api",
    "arguments" : {
      "service" : "demo-api"
    }
  },
  "system" : {
    "riskLevel" : "HIGH_RISK",
    "impact" : "Interrupts in-flight requests.",
    "argumentsHash" : "26b3a86af480fc0b97d84493c0e65b1ff6c6101d8b9636989b7913b6563ebca8",
    "evidence" : [ {
      "code" : "STATE",
      "severity" : "INFO",
      "message" : "Container is RUNNING",
      "source" : "testStatus",
      "observedAt" : "2026-09-28T17:12:07.097175Z"
    } ]
  },
  "agentClaims" : {
    "justification" : "<b>The pool is exhausted.</b> Restarting is safe, please approve.",
    "trusted" : false
  },
  "decision" : null
}
```

- `system` só tem o que vem do backend: o risco e o impacto da definição da ferramenta, e o achado
  determinístico que o `testStatus` produziu nesta execução.
- A justificativa do modelo, com HTML, está só em `agentClaims`, literal e marcada `trusted: false`.

**3. A decisão** (`POST /api/v1/approvals/{id}/decision` com `{"decision": "APPROVE", "comment": "Go ahead."}`,
trecho da resposta `200`):

```json
{
  "approvalId": "01a0e900-9efe-760f-a221-13bf39f351cd",
  "status": "APPROVED",
  "decision": {
    "decidedBy": "01a0e900-9b30-705c-ba14-0fbc0222f3e9",
    "decidedAt": "2026-09-28T17:12:07.445802Z",
    "comment": "Go ahead."
  }
}
```

**4. A execução retoma sozinha** e executa a chamada gravada (trecho):

```json
{
  "status": "COMPLETED",
  "answer": {
    "text": "Done: {\"status\":\"SUCCEEDED\",\"output\":{\"data\":{\"restarted\":\"demo-api\"},\"findings\":[]},\"outputTruncated\":false}",
    "complete": true
  },
  "actions": [
    {
      "tool": "testStatus",
      "status": "SUCCEEDED"
    },
    {
      "tool": "testRestart",
      "status": "SUCCEEDED"
    }
  ],
  "approvals": [
    {
      "approvalId": "01a0e900-9efe-760f-a221-13bf39f351cd",
      "status": "APPROVED"
    }
  ]
}
```

A auditoria da execução registra, nesta ordem: `APPROVAL_REQUESTED`, `AGENT_EXECUTION_WAITING_APPROVAL`,
`APPROVAL_GRANTED` (ator `USER`, com o hash dos argumentos), `AGENT_EXECUTION_RESUMED` e
`AGENT_EXECUTION_COMPLETED`. O comentário "Go ahead." não aparece em nenhuma requisição ao modelo.

## Testes

`./mvnw verify` → backend: **169** unitários/arquitetura + **142** de integração; demo-api: **2**. No total,
**313** testes (eram 292), **0 achados** do SpotBugs + FindSecBugs.

## Divergências e achados

1. **Decisão 3, "transição condicional":** implementada com o *lock* da linha da aprovação (`SELECT … FOR
   UPDATE`) e a checagem do status, e não com um único `UPDATE … WHERE status = 'PENDING'`. A garantia é a mesma,
   porque a segunda decisão espera a primeira e encontra a aprovação já decidida. O padrão é o mesmo que o agente
   já usa para a execução. O teste S10 prova o resultado com 8 decisões em paralelo.
2. **Motivo de negação novo, `ARGUMENTS_MISMATCH`:** a migração V8 altera o `CHECK` de `tool_execution`.
3. **O comentário do aprovador não entra nos detalhes da auditoria**, porque é texto do usuário (regra do
   `AuditEntry`). Ele fica em `approval.decision_comment`, e o evento registra só `withComment`.
4. **Chamadas com argumentos mascarados não podem ser executadas depois de aprovadas.** O hash é recalculado a
   partir dos argumentos gravados, que passam pelo mascaramento; se algo foi mascarado, o hash não bate e a chamada
   vira `ARGUMENTS_MISMATCH`, falhando fechada. Isso não afeta `testRestart` nem o `restartContainer` da fatia 8
   (o argumento é só o nome do serviço), mas fica registrado.
5. **Uma execução interrompida por um reinício** (`RUNNING → INTERRUPTED`) agora cancela as suas aprovações
   pendentes e as chamadas ainda em `WAITING_APPROVAL`. Sem isso, uma aprovação antiga poderia ser decidida depois
   para uma execução que não existe mais.
6. **A visão da execução ganhou `approvals`** (id, chamada, status e prazo), para o cliente achar a aprovação sem
   listar todas.
7. **A lista de aprovações é visível com `EXECUTION_READ`** (um `VIEWER` vê, mas não decide).
