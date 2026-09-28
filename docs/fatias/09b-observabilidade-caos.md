# Fatia 9b — Observabilidade e caos

> Status: **em revisão**. Métricas, dashboard e os cinco cenários de caos estão prontos e documentados; as
> observações O-9b-1 e O-9b-2 aguardam decisão. Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 9.

## Objetivo

Tornar o próprio AaaS observável, com quatro métricas e um dashboard, e **observar** o que ele faz quando as
fronteiras falham: o backend morre, reinicia, perde o proxy, perde o LLM, ou perde o proxy no meio de um
restart. **Regra:** um comportamento inesperado vira primeiro evidência e diagnóstico. Nenhum foi corrigido nesta
etapa.

## Métricas novas

| Métrica | Tipo | Tags | Quando conta |
|---|---|---|---|
| `devops.approvals` | contador | `status` (`PENDING` ao pedir; `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED` ao terminar), `tool` | Em cada transição, **depois do commit**. Uma decisão desfeita junto com a sua auditoria (TM-B7-07) não aparece |
| `devops.approval.wait` | timer, com histograma | `status` | Do pedido até a decisão, a expiração ou o cancelamento |
| `devops.tool.restart.verification` | contador | `verification` | Quando a verificação termina, ou seja, só para restarts que o runtime aceitou. Um `OUTCOME_UNKNOWN` nunca chega aqui |
| `devops.tool.restart.verification.duration` | timer, com histograma | `verification` | Do restart aceito até o resultado da verificação |

As tags vêm de enums e do catálogo fechado de ferramentas, então a cardinalidade é limitada (TM-X-02).

**Provas:**
- `ApprovalIT.approvalMetrics_countEachCommittedTransitionOnce`: 2 pedidos, 1 aprovação, 1 rejeição; a decisão atrasada (`409`) não conta.
- `ApprovalIT.theDecisionAndItsAuditEvent_areAtomic`: a decisão desfeita não conta.
- `RestartContainerToolTest.everyVerification_isCountedAndTimed_byItsOutcome_andAnUnknownOutcomeIsNot`.

## Dashboard "Agente" (`DevOps Agent — Agent`)

`observability/grafana/dashboards/devops-agent-agent.json`, provisionado na pasta *DevOps Agent*. O dashboard
tem 14 painéis, cada um respondendo a uma pergunta:

| Pergunta | Painel |
|---|---|
| Quantas execuções ocorreram, e como terminaram? | *Agent executions*, *Executions by final status* |
| Quantas precisaram de aprovação, e como terminaram? | *Approvals requested*, *Approvals by status* |
| Quanto tempo esperaram? | *Approval wait (p50 / p95)* |
| Quantos restarts foram pedidos, e como terminaram? | *restartContainer calls by status* |
| Quantos foram verificados, e com que resultado? | *Restarts verified*, *Restart verification*, *Restart verification duration (p95)* |
| Quantos terminaram como `OUTCOME_UNKNOWN`? | *Restarts with unknown outcome* (ver O-9b-1) |
| O que a política negou? | *Policy denials by reason* |
| Quanto LLM foi consumido? | *LLM calls by outcome*, *LLM tokens*, *LLM estimated cost* |

**Os valores são contados desde a subida do backend.** A primeira versão usava `increase(...[$__range])`, e,
conferida no compose, mostrou zeros. O Prometheus não conta o primeiro incremento de uma série que nasce com o
próprio evento, e num sistema de pouco volume quase todo evento é o primeiro. Por isso os painéis leem o valor
do contador, e os quantis usam o histograma acumulado. O efeito colateral é que os contadores zeram quando o
backend reinicia.

**Prova:** `RestartContainerIT.everySeriesOfTheAgentDashboard_isExposed_withTheLabelsItGroupsBy` lê o JSON do
dashboard, extrai as 10 séries `devops_*` e exige cada uma no *scrape* real, depois de um restart aprovado e de
uma negação. As séries novas também têm de estar lá com os *labels* por que os painéis agrupam. Uma métrica
renomeada ou um erro de digitação no dashboard falha no CI, em vez de virar um painel vazio.

**No compose (2026-09-28):** o Grafana carregou o dashboard (14 painéis, pasta *DevOps Agent*). Depois dos
cenários e de um restart normal da `demo-api`, as consultas devolveram:
- verificação `HEALTHY` = 1, com p95 de 7,1 s;
- p95 da espera pela aprovação = 5,6 s;
- aprovações `PENDING` = 2 e `APPROVED` = 2;
- chamadas `restartContainer`: `WAITING_APPROVAL` = 2, `SUCCEEDED` = 1, `OUTCOME_UNKNOWN` = 1.

## Os cinco cenários de caos

Todos rodaram no compose com a imagem desta branch e o provedor `scripted`. Um container `chaos-slow`
(`alpine sleep`, que ignora o SIGTERM) foi posto na allowlist de um segundo ambiente com o nome lógico
`demo-api`. Assim, o restart leva cerca de 10 s: a parada graciosa, e depois o SIGKILL. **A evidência
independente do efeito é o `docker events` do próprio daemon**, e não o que o backend acredita.

| # | Cenário | O que foi feito | Observado | Esperado? |
|---|---|---|---|---|
| 1a | `kill -9` no backend com uma execução esperando aprovação | `docker kill -s KILL` e `docker start` | Execução continuou `WAITING_APPROVAL`, aprovação `PENDING`; recuperação: "0 interrupted, 0 unknown". Aprovada depois: restart `SUCCEEDED`, `demo-api` `healthy` | Sim |
| 1b | `kill -9` no backend **durante** um restart em andamento | Aprovação às 22:29:34; `kill -9` às 22:29:37, com a chamada `RUNNING` | **O Docker concluiu o restart assim mesmo:** `kill` 22:29:34 (SIGTERM), `kill` + `die` + `start` + `restart` 22:29:44; `StartedAt` novo. Na subida: chamada `OUTCOME_UNKNOWN`, execução `INTERRUPTED/BACKEND_RESTARTED`, aprovação `APPROVED`. Depois de mais de 2 varreduras (70 s): **1** restart só | Sim. O contador do painel de `OUTCOME_UNKNOWN` não registra este caso (O-9b-1) |
| 2 | Restart **gracioso** do backend durante um restart em andamento | `docker compose restart backend` 3 s depois da aprovação | O desligamento gracioso terminou em cerca de 1 s (código 0) e **não esperou** a chamada. A *thread* da ferramenta foi interrompida (`InterruptedException`), e o executor registrou "outcome unknown" no log, mas o pool do banco fechou logo depois e o resultado não foi gravado. Na subida: chamada `OUTCOME_UNKNOWN` pela recuperação, execução `INTERRUPTED`. O Docker concluiu o restart (`StartedAt` 22:31:47) | Estado correto, mas o restart gracioso se comporta como o `kill -9` para uma chamada em andamento (O-9b-2) |
| 3 | Proxy indisponível | `docker compose stop docker-socket-proxy` | `connectivity-check` → `reachable: false`; `services/status` → `503`. Diagnóstico: `getContainerStatus` `FAILED/RUNTIME_UNAVAILABLE`, execução `COMPLETED` dizendo isso. Restart aprovado com o proxy fora: `FAILED/RUNTIME_UNAVAILABLE` "the restart was not sent" (caso E, agora ao vivo), **0** restarts. Proxy de volta: o backend voltou a alcançá-lo sozinho em até 7 s | Sim. A primeira checagem, 2 s depois de subir o proxy, ainda falhou porque o proxy não tinha terminado de subir, não por conexão velha |
| 4 | LLM indisponível | Provedor `openai` com chave e preços falsos e URL numa porta fechada (arquivo de *override* fora do repositório) | Execução `FAILED/LLM_UNAVAILABLE` em 2 s; 1 `llm_call` com `ERROR/UNAVAILABLE`, 2 retentativas; **0** chamadas de ferramenta; a chave falsa não aparece no log | Sim |
| 5 | **Proxy cai durante um restart** | Aprovação às 22:35:39; `docker kill -s KILL` no proxy às 22:35:42, com a chamada `RUNNING` | **Enviado:** o daemon registrou `kill` às 22:35:39. **Resultado desconhecido:** a chamada virou `OUTCOME_UNKNOWN` na hora ("The call failed after it may have reached the runtime."), e o modelo recebeu só isso; execução `COMPLETED`. **Efeito real:** `kill`, `stop`, `die`, `start` e `restart` às 22:35:49; `StartedAt` novo. **Sem retentativa:** com o proxy de volta e mais de 2 varreduras, **1** restart só | Sim |

**O padrão dos cenários 1b, 2 e 5:** nos três, a conexão caiu depois de o pedido chegar ao Docker, e o Docker
**completou** o restart. O sistema registrou `OUTCOME_UNKNOWN` nos três, e não `FAILED` nem `SUCCEEDED`. Não
inventou um resultado, e em nenhum caso enviou um segundo pedido.

## Observações

| # | Observação | Evidência | Muda o modelo de segurança? | Estado |
|---|---|---|---|---|
| O-9b-1 | O `OUTCOME_UNKNOWN` atribuído **pela recuperação** não passa pelo executor e não entra no `devops.tool.executions`. Por isso o painel *Restarts with unknown outcome* só conta os casos vistos ao vivo (cenário 5), e não os de uma queda (1b, 2). Os contadores também zeram quando o backend reinicia. A auditoria (`TOOL_EXECUTION_OUTCOME_UNKNOWN`) e o banco registram todos | Cenário 1b: depois da subida, o *scrape* mostra `devops_agent_executions_total{status="INTERRUPTED"} 1` e nenhuma série `OUTCOME_UNKNOWN` | Não: é observabilidade; a fonte da verdade continua certa | Aguardando decisão |
| O-9b-2 | O desligamento gracioso não espera as chamadas de ferramenta em andamento. A *thread* é interrompida, o resultado que o executor calcula (`OUTCOME_UNKNOWN`) não é gravado porque o pool já fechou, e quem resolve é a recuperação na próxima subida. Na prática, para uma chamada em andamento, o restart gracioso equivale ao `kill -9` | Cenário 2: logs com o `InterruptedException` às 22:31:40.372, o fechamento do JPA às 22:31:40.380 e a chamada ainda `RUNNING` na subida | Não: o estado final é o mesmo e correto (`OUTCOME_UNKNOWN`), e não há segundo restart | Aguardando decisão |
| O-9b-3 | O painel com `increase()` mostrava zeros | Consultas ao Prometheus no compose | Não | **Resolvido nesta etapa** (é o próprio dashboard da 9b): painéis contam desde a subida |

### Opções para as observações em aberto

**O-9b-1**
- **(A) Documentar:** o painel conta os casos ao vivo, e a auditoria e o banco contam todos.
- **(B) Contar também na recuperação:** `ToolExecutionHistory.recoverInterrupted` incrementaria o mesmo contador. O zeramento na subida continua, porque é próprio dos contadores do Prometheus.

**O-9b-2**
- **(A) Documentar:** o estado já fica correto na próxima subida.
- **(B) Esperar as chamadas em andamento no desligamento gracioso**, com um limite abaixo do tempo que o Docker dá antes do SIGKILL (10 s no compose). É uma mudança de ciclo de vida: o `restartContainer` pode levar até 90 s, então o limite resolveria só parte dos casos.

## Testes

`./mvnw verify`: **519 testes** (356 unitários e 161 de integração no backend, e 2 no `demo-api`), 0 falhas,
SpotBugs sem achados. Os 161 de integração também passaram em ordem alfabética reversa. Na 9a eram 516.

| Teste novo | O que prova |
|---|---|
| `RestartContainerToolTest.everyVerification_isCountedAndTimed_byItsOutcome_andAnUnknownOutcomeIsNot` | As duas métricas do restart, por resultado; um `OUTCOME_UNKNOWN` não conta como verificado |
| `ApprovalIT.approvalMetrics_countEachCommittedTransitionOnce` | `devops.approvals` e `devops.approval.wait` contam cada transição gravada uma vez |
| Asserção nova em `ApprovalIT.theDecisionAndItsAuditEvent_areAtomic` | Uma decisão desfeita não aparece na métrica |
| `RestartContainerIT.everySeriesOfTheAgentDashboard_isExposed_withTheLabelsItGroupsBy` | O dashboard só consulta séries que existem, com os *labels* certos |

Os cenários de caos são manuais e estão registrados acima com os horários e os eventos do daemon. Eles não
viraram testes de CI: matam e reiniciam containers do compose, e o objetivo aqui era observar.
