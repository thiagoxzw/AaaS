# Fatia 4 — Agente + ScriptedLlmGateway

> Status: **implementada** (2026-09-27). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 4.

## Objetivo

Provar que o loop do agente é **controlado pelo backend**, limitado, persistido, idempotente e testável sem um
LLM real. O LLM é um componente substituível atrás de um port: ele propõe, e o código decide, executa, registra
e para.

**Fica de fora:** o LLM real (fatia 6), a aprovação e a retomada depois dela (fatia 7) e os achados
determinísticos (fatia 5).

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | Dois módulos, `llm` e `agent`. `agent → llm`, `agent → tool`; `llm` e `tool` não conhecem `agent` |
| 2 | Dispatcher in-process: 4 execuções simultâneas e fila de 50, configuráveis. Com a fila cheia, `503` com `Retry-After`, **antes** de gravar qualquer coisa |
| 3 | Execuções `QUEUED` na inicialização voltam para a fila, porque nunca começaram |
| 4 | Cancelamento cooperativo: o passo em andamento termina e fica registrado |
| 5 | Só quem criou a conversa envia mensagens nela; `EXECUTION_READ` lê as execuções da organização |
| 6 | `llm_call_id` `NOT NULL`; a migração falha diante de linhas órfãs, sem "consertar" os dados |
| 7 | S4 adaptado: `VIEWER` barrado na API; a revogação durante a execução prova o RNF-SEG-13 |
| 8 | Saída truncada → `COMPLETED` + `LLM_OUTPUT_TRUNCATED`, e a API marca a resposta como **parcial** |
| + | `ScriptedLlmGateway` com um `ScriptRepository` separado: carregar e escolher roteiros é responsabilidade do provedor `scripted`, não do gateway |
| + | O orçamento é contado **antes** da política, inclusive para propostas inválidas ou negadas |
| + | Auto-merge **não** é usado; o fluxo continua com revisão manual |

## Estrutura

```
llm/            LlmGateway (port), LlmRequest, LlmResponse, LlmMessage, LlmToolSpec, LlmToolCall, LlmException,
                LlmProperties, LlmConfiguration (provedor inexistente = falha na subida)
llm/scripted/   ScriptedLlmGateway, ScriptRepository, ClasspathScriptRepository, LlmScript
agent/          Conversation, Message, AgentExecution, LlmCall; ConversationService (aceita a mensagem),
                ExecutionDispatcher, AgentOrchestrator (o loop), ExecutionJournal (transições sob lock),
                LlmRequestFactory (reconstrói o histórico a partir dos registros), ContextBuilder,
                ExecutionQueries, ExecutionCancellation, AgentStartupRecovery, SystemPrompt (versionado)
agent/api/      POST /conversations, POST /conversations/{id}/messages, GET /executions/{id},
                POST /executions/{id}/cancel
db/migration/   V7: conversation, message, agent_execution, llm_call + FKs da tool_execution
```

```
                    ┌──────────── agent ────────────┐
POST …/messages ──► ConversationService ─► Dispatcher ─► AgentOrchestrator
  (202)              mensagem + execução QUEUED           │  checkpoint (status, orçamentos)
                     numa transação                        │  LlmRequestFactory (registros → histórico)
                                                           ▼
                                                   LlmGateway (port) ── ScriptedLlmGateway ── ScriptRepository
                                                           │
                                   conta a proposta ◄──────┘
                                           ▼
                                   ToolExecutor (fatias 2 e 3): política → ferramenta → adapter → proxy → Docker
```

## O loop

```
QUEUED ─► RUNNING
  a cada volta:
    checkpoint sob lock: ainda RUNNING? sobrou orçamento de iterações e de tempo ativo?
    reconstrói o pedido a partir dos registros, com as ferramentas que o usuário pode usar AGORA
    chama o LLM (sem transação aberta) ─► grava a llm_call, mesmo que a execução tenha sido cancelada
    falha do LLM ─► FAILED (LLM_<categoria>)
    propostas: conta cada uma ─► ToolExecutor (a mesma cadeia de política das fatias 2 e 3)
               a primeira além do limite é registrada (e negada); o resto da volta é descartado ─► BUDGET_EXCEEDED
               alguma ficou WAITING_APPROVAL ─► a execução para em WAITING_APPROVAL (retomada: fatia 7)
    sem propostas: o texto vira a mensagem ASSISTANT ─► COMPLETED
```

- **Tudo sob lock:** cada transição da execução acontece numa transação curta, com `SELECT … FOR UPDATE` na
  linha da `agent_execution`. O worker confere o status dentro desse lock a cada passo, então um cancelamento
  que chega entre dois passos é sempre visto.
- **Nada em memória entre voltas:** o pedido ao LLM é reconstruído a cada volta a partir de `message`,
  `llm_call` e `tool_execution`. Isso é o que vai permitir retomar a execução depois de uma aprovação.
- **O que o LLM vê de uma ferramenta:** a saída já sanitizada e mascarada pelo executor; de uma negação, só
  `{status: DENIED, reason}`; nunca nomes reais de container.
- **Permissões relidas a cada volta:** o catálogo oferecido ao LLM e a política usam as permissões atuais do
  usuário (RNF-SEG-13).

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| O LLM não controla o fluxo | O orchestrator decide cada passo; o gateway só traduz | ArchUnit: `llm` não depende de `agent`, `tool`, `environment`, `audit` nem `integration`; `agent` não fala com o runtime direto (`agent_does_not_call_runtimes_directly`) |
| A segurança não depende do modelo | Roteiros que **obedecem** à injeção | S1, S2, S4, S5, S7, S8 em `AgentIT`; S9 em `RealDockerIT` (tabela no documento 06 §6) |
| Orçamento contado antes da política | `ExecutionJournal.countToolCall` antes do `ToolExecutor` | `s8_repeatingDeniedProposals_exhaustsTheToolCallBudget`: 11 propostas registradas (todas negadas), `MAX_TOOL_CALLS` |
| Limite de iterações e de tempo | Checkpoint sob lock a cada volta; o tempo esperando aprovação não conta | `s8_aModelThatNeverStops_exhaustsTheIterationBudget`, `AgentExecutionTest` |
| S9: segredo fora do banco **e** fora do LLM | Adapter sem `Config.Env` (fatia 3) + saída limpa pelo executor | `RealDockerIT.s9_…`: container real com `DB_PASSWORD`; confere a coluna `output` **e** cada requisição enviada ao LLM |
| Ações vêm dos registros | `actions[]` é uma projeção da `tool_execution` | `actionsComeFromTheRecords_notFromTheModelsText` |
| Idempotência (RF-27) | `Idempotency-Key` + hash do corpo; índice único parcial `(requested_by, idempotency_key)` | mesma chave → mesma execução (`replayed: true`); corpo diferente → `422`; formato inválido → `422` |
| Uma execução ativa por conversa | Índice único parcial (invariante 2) + verificação na aplicação | `aSecondMessage_whileAnExecutionIsActive_is409`, `PersistenceGuaranteesIT.aConversation_cannotHaveTwoActiveExecutions` |
| FKs obrigatórias da fatia 2 | V7: FKs compostas + `llm_call_id NOT NULL` | `PersistenceGuaranteesIT.toolExecution_rejectsAnUnknownExecution_orAnotherOrganizationsLlmCall` |
| Cancelamento cooperativo (RF-23) | O passo em andamento termina; o próximo checkpoint vê `CANCELLED` | `cancellingDuringAModelCall_…`: a `llm_call` fica registrada e a proposta dela nunca roda; `cancellingAnExecutionWaitingForApproval_…`: a chamada pendente vira `CANCELLED` |
| Falha do LLM (RNF-CONF-10) | `LlmException` por categoria → `FAILED` + `llm_call` com `ERROR` | `aFailingModel_endsTheExecutionAsFailed_withAClearReason` |
| Resposta truncada marcada como parcial | `LLM_OUTPUT_TRUNCATED` + `answer.complete: false` | `aTruncatedAnswer_isCompleted_butMarkedAsPartial` |
| Recuperação (RNF-CONF-08/09) | `RUNNING` → `INTERRUPTED`; chamadas `RUNNING` → `OUTCOME_UNKNOWN` (ator `SYSTEM`); `QUEUED` volta para a fila; `WAITING_APPROVAL` não muda | `AgentRecoveryIT` |
| Isolamento por organização | Tudo com `organization_id` e FKs compostas; consultas com a organização | `onlyTheCreatorSendsMessages_andOtherOrganizationsSeeNothing` (`403` para colega, `404` para outra organização) |
| Contexto reconstruível | `context_snapshot` (ambiente, serviços por nome lógico, 5 ações recentes, janela da conversa) + `prompt_version` + `llm_model` | `aQuestion_runsTheLoop_andEverythingIsRecorded` |
| Memória de conversa | Janela das últimas 10 mensagens | `theConversationHistory_isSentBack` |
| Provedor inexistente | `LLM_PROVIDER=openai` falha na subida | `LlmConfigurationTest` |

## Testes

`./mvnw verify` → backend: **122** unitários/arquitetura + **113** de integração; demo-api: **2**. No total,
**237** testes (eram 196), **0 achados** do SpotBugs + FindSecBugs.

## Demonstração

Rodei a stack com `docker compose up`, sobre o banco que já existia da demonstração da fatia 3: a V7 foi
aplicada a dados existentes, e a recuperação na inicialização rodou (nada a recuperar).

| Passo | Resultado |
|---|---|
| `POST /conversations/{id}/messages` "o demo-api está de pé?" com `Idempotency-Key` | `202`, `Location: /api/v1/executions/…`, `status: QUEUED` |
| A mesma requisição de novo | `202`, o **mesmo** `executionId`, `replayed: true` |
| `GET /executions/{id}` | `COMPLETED`; `actions`: `getContainerStatus` → `demo-api` → `SUCCEEDED`; `budget`: 1/10 chamadas, 2/8 iterações; `llmModel: scripted-v1`; `promptVersion: agent-system-v1` |
| `/chaos/unhealthy`, esperar o healthcheck, perguntar "status now?" | A resposta traz `state: RUNNING`, `health: UNHEALTHY`, lido do Docker real |
| "show me the logs" | `getContainerLogs` → `SUCCEEDED` |
| Auditoria da execução | `AGENT_EXECUTION_REQUESTED` (USER) → `AGENT_EXECUTION_STARTED` → `TOOL_EXECUTION_STARTED` → `TOOL_EXECUTION_SUCCEEDED` → `AGENT_EXECUTION_COMPLETED` (AGENT) |
| Log do proxy | só `GET /containers/devops-demo-api/json` e `…/logs` |

## Divergências e achados

1. **Cancelar é só para quem iniciou a execução** (`403` para os outros). O desenho dizia apenas
   `AGENT_INTERACT`. Segui a mesma lógica da decisão 5: o agente age em nome de uma pessoa, e só ela o interrompe.
2. **Resposta vazia do LLM** (sem texto e sem propostas) vira `FAILED` com `LLM_EMPTY_RESPONSE`. Não estava no
   desenho.
3. **O ambiente desativado durante a execução** encerra a execução com `FAILED` / `ENVIRONMENT_UNAVAILABLE`.
4. **Formato da `Idempotency-Key`:** de 8 a 128 caracteres (letras, dígitos, `.`, `_`, `:`, `-`). Fora disso, `422`.
5. **Propostas descartadas:** depois da primeira proposta além do limite, as demais daquela resposta do LLM não
   viram registros (senão um LLM malicioso poderia gerar milhares de linhas numa só resposta). Elas contam na
   métrica `devops.agent.proposals.dropped`. O contador `tool_call_count` mostra 11 de 10: a 11ª foi a que
   estourou.
6. **Propostas depois de uma que exige aprovação:** todas as propostas daquela resposta são processadas (as
   read-only rodam), e só então a execução para em `WAITING_APPROVAL`. A retomada é da fatia 7.
7. **O snapshot volta do banco normalizado:** o `jsonb` do PostgreSQL reordena as chaves e muda os espaços. O
   conteúdo é o mesmo; o teste compara por padrão, não por texto exato.
8. **Um gateway interceptador só nos testes:** `InterceptingLlmGateway` (código de teste) registra o que o "LLM"
   viu e roda ganchos por marcador, para tornar determinísticos o S4 (revogar a permissão durante a chamada) e o
   cancelamento. Nada disso existe no código de produção.
9. **A recuperação na inicialização fica desligada nos testes de integração** (eles compartilham um banco) e é
   exercida chamando o método diretamente em `AgentRecoveryIT`.
10. **O `503` da fila cheia** é provado no teste unitário do `ExecutionDispatcher`; um teste de integração exigiria
    um contexto Spring próprio só para isso.
11. **Tokens e custo ficam em zero** com o provedor `scripted`. A tabela de preços chega com o adapter real
    (fatia 6).
12. **A resposta do `scripted` inclui o JSON da ferramenta.** Não é um modelo: os roteiros de demonstração
    mostram o que a ferramenta devolveu, e o texto diz isso explicitamente.
13. **Demonstração:** o healthcheck do `demo-api` precisa de cerca de 15 s (3 falhas a cada 5 s) para marcar
    `UNHEALTHY`. Na primeira tentativa perguntei cedo demais e o agente respondeu `HEALTHY`, que era o estado
    real do Docker naquele momento.
14. **SpotBugs (17 achados):**
    - `URF_UNREAD_FIELD` (11): as entidades ganharam os getters que faltavam;
    - `CRLF_INJECTION_LOGS` (3): suprimidos, porque os valores logados são UUIDs gerados pela aplicação;
    - `UNSAFE_HASH_EQUALS` (1): a comparação do hash da requisição usa `MessageDigest.isEqual`;
    - `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` (1): a liberação da vaga na fila passou para um `finally`;
    - `CT_CONSTRUCTOR_THROW` (1): o `ClasspathScriptRepository` virou `final`;
    - na segunda rodada: `NP_NULL_ON_SOME_PATH` (1) no resultado do `TransactionTemplate`, corrigido com
      `requireNonNull`; e a supressão de log do dispatcher teve de ir para o método que de fato loga, fora da
      lambda.
