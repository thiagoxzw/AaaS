# Fatia 2 — Framework de ferramentas + Fake Runtime

> Status: **implementada** (2026-09-26). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 2.

## Objetivo

Provar que a cadeia **Registry → Policy → Executor → Tool** funciona sem Docker e sem LLM, e que as garantias
de segurança existem **antes** da primeira ferramenta real. Esta fatia prova o **framework** e não finge
integração: o catálogo de produção fica vazio até a fatia 3.

```
Fatia 2:  ToolRegistry → PolicyEngine → ToolExecutor → FakeContainerRuntime → ferramentas de teste
Fatia 3:  ToolRegistry → PolicyEngine → ToolExecutor → DockerEngineContainerRuntime → docker-socket-proxy
```

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | A FK `tool_execution → agent_execution` fica para a fatia 4, registrada como **migração obrigatória** (critério de aceite da fatia 4 no documento 07, e na ADR-0009) |
| 2 | JSON Schema com gerador próprio (`FlatRecordSchemaGenerator`), atrás da interface `JsonSchemaGenerator` |
| 3 | Catálogo de produção vazio até a fatia 3 |
| 4 | Auditoria das chamadas com ator `AGENT` e `on_behalf_of_user_id` |
| 5 | Sanitização e mascaramento campo a campo, antes de qualquer persistência |
| 6 | 16 execuções simultâneas, configurável |
| 7 | Até 3 retentativas adicionais, só para ferramentas `retryable` (read-only) e falhas transitórias |
| 8 | O registry mantém o catálogo **completo**; o endpoint aplica o filtro de capacidade (`ToolCatalog`) |

## Estrutura

```
tool/
  api/        Tool<I>, ToolInput, ToolDefinition (+ builder), ToolResult, ToolExecutionContext, RiskLevel,
              ApprovalRequirement, ToolCategory, Finding, ToolErrorCode, @Description
  registry/   ToolRegistry (catálogo completo, validado ao subir), JsonSchemaGenerator, FlatRecordSchemaGenerator
  policy/     PolicyEngine (cadeia de validação), PolicyRules (matriz risco × autonomia), ArgumentBinder,
              ToolCatalog (filtro de capacidade), PolicyDecision, DenialReason
  container/  port ContainerRuntime, ContainerRef, TargetResolver, tipos do domínio (sem variáveis de ambiente)
  execution/  ToolExecutor, ToolExecutionJournal, ToolExecution, OutputProcessor, OutputSanitizer, SecretRedactor
  web/        GET /api/v1/tools?environmentId=…

tool → environment (EnvironmentDirectory), audit, shared      identity implementa shared.PermissionLookup
```

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| Definição inválida impede a subida (documento 05, §3.1) | o `ToolRegistry` valida as invariantes no construtor | `ToolRegistryTest`: retentativa em ferramenta com efeito colateral, `DESTRUCTIVE` sem `ALWAYS`, falta de impacto, alvo inválido, timeout fora do limite, nome inválido, entrada não plana, nome duplicado |
| Entradas planas (o LLM não passa estruturas arbitrárias) | `FlatRecordSchemaGenerator` recusa mapas, listas e objetos | `rejectsMapsAndLists_soTheLlmCannotPassArbitraryStructures` |
| Cadeia de validação na ordem (documento 03, §5.2) | `PolicyEngine`: ambiente → ferramenta → autonomia → argumentos → allowlist → permissão atual → orçamento → aprovação | `PolicyEngineTest` (ordem) + `ToolExecutorIT` (cada motivo, com persistência e auditoria) |
| Fail-closed | qualquer exceção na avaliação vira `DENY/POLICY_ERROR` | `anUnexpectedError_failsClosed` |
| Matriz risco × autonomia (documento 03, §5.3) | `PolicyRules`, a mesma para a política e para o catálogo | `PolicyRulesTest` (matriz completa) |
| O agente nunca tem mais poder que o usuário | as permissões **atuais** do solicitante são lidas por `PermissionLookup` a cada proposta | `theAgentNeverHasMorePowerThanTheRequester`, `revokedPermission_isEnforcedOnTheNextProposal` |
| Allowlist por construção | `ContainerRef` só é criado pelo `TargetResolver`, a partir de um serviço **habilitado** de um ambiente **ativo** | `servicesOutsideTheAllowlist_orDisabled_areNotResolvable` |
| Argumentos estritos | JSON estrito: campos desconhecidos, tokens extras e coerção de tipos (`"2"` → `2`) são recusados; depois vem o Bean Validation | `invalidArguments_areDenied` (4 variações, inclusive `"demo-api\"; rm -rf /"`) |
| `RUNNING` gravado antes da chamada | duas transações curtas; nenhuma fica aberta durante a chamada externa | `executionIsCommittedAsRunning_beforeTheToolIsCalled` (a ferramenta de teste lê a própria linha no banco) |
| Timeout com efeito colateral nunca é repetido | read-only → `TIMED_OUT`; com efeito colateral → `OUTCOME_UNKNOWN`, uma única tentativa | `readOnlyTimeout_endsAsTimedOut`, `sideEffectTimeout_endsAsOutcomeUnknown_andIsNeverRetried` |
| Retentativa limitada | até 3 extras, backoff exponencial, só para `retryable` e falha transitória | `retryableReadOnlyTool_isRetriedOnTransientFailures` (3 tentativas), `retries_stopAfterThreeExtraAttempts` (4) |
| Erro inesperado não vaza | `FAILED/INTERNAL_ERROR` com mensagem genérica; o stack trace vai só para o log | `unexpectedException_endsAsInternalError_withoutLeakingItsMessage` |
| Segredos nunca chegam ao banco em texto puro | `OutputProcessor`: cada string é sanitizada e depois mascarada, e só então o tamanho é limitado | `secrets_neverReachTheDatabaseInPlainText` (lê a coluna `output` direto no banco), `argumentsWithSecrets_areStoredRedacted` |
| Caracteres invisíveis ficam visíveis | bidi e zero-width viram `<U+202E>`, `<U+200B>`… | `OutputSanitizerTest` |
| Saída grande não corrompe o JSON | acima do limite, vira `{"truncated": true, "originalBytes": …, "preview": "…"}` | `oversizedOutput_isReplacedByAValidPreview`, `OutputProcessorTest` |
| Ação de risco espera o humano | `REQUIRE_APPROVAL` → `WAITING_APPROVAL`, e o runtime nunca é chamado | `highRiskCall_waitsForApproval_andIsNotExecuted` |
| Auditoria `AGENT` em nome do usuário | `AuditEntry.byAgentOnBehalfOf`; o recurso é o serviço-alvo | `allowedReadOnlyCall_runsThroughThePort_andIsPersistedAndAuditedAsAgentOnBehalfOfTheUser` |
| Catálogo filtrado | `ToolCatalog`: permissão do usuário + autonomia do ambiente; `requiresApproval` calculado para aquele ambiente | `ToolCatalogIT` (operator, viewer, `OBSERVE_ONLY`, schema publicado, `404` em outra organização) |
| Fronteiras | ArchUnit: `environment`/`audit` não dependem de `tool`; implementações de `Tool` não usam JPA, JDBC, HTTP nem auditoria | `ArchitectureTest` |

## Testes

`./mvnw verify` → **80** unitários/arquitetura + **63** de integração (**143** no total), **0 achados** do
SpotBugs + FindSecBugs.

## Demonstração

Como aprovado, a demonstração desta fatia é a suíte de integração rodando a aplicação real, com PostgreSQL e as
ferramentas de teste. Além dela, conferi a aplicação de **produção** (sem ferramentas e sem `ContainerRuntime`):

| Verificação | Resultado |
|---|---|
| Subida sobre o banco com dados da fatia 1 | V6 aplicada (inclusive o `ALTER` na allowlist já populada); backend `healthy` |
| `GET /api/v1/tools?environmentId=<local>` | `[]`: catálogo de produção vazio, como previsto |
| `GET /api/v1/tools` com ambiente inexistente | `404` |

## Divergências e achados

1. **Dois motivos de negação novos:**
   - `ENVIRONMENT_UNAVAILABLE`, quando o ambiente não existe, é de outra organização ou está desativado;
   - `POLICY_ERROR`, que é o *fail-closed*.

   Os dois estão no `CHECK` do banco e no documento 04.
2. **Código de erro novo, `CAPACITY_EXCEEDED`:** a chamada foi permitida, mas nenhuma das 16 vagas de execução
   abriu antes do timeout da ferramenta. Ela **nunca** foi iniciada, então a falha é segura.
3. **Auditoria `TOOL_CALL_AWAITING_APPROVAL`:** cobre o RF-44 ("toda proposta é auditada") até a entidade
   `Approval` chegar na fatia 7, que acrescentará `APPROVAL_REQUESTED`.
4. **Retentativa depois de timeout:** o timeout consome o prazo inteiro da ferramenta, então na prática não
   sobra tempo para repetir. As retentativas cobrem falhas transitórias **rápidas**, como runtime
   indisponível. É uma consequência de respeitar o timeout total, que foi a regra aprovada.
5. **Classificação conservadora para ferramentas com efeito colateral:** qualquer falha que não seja uma
   recusa definitiva do runtime (`NOT_FOUND`, `FORBIDDEN`) vira `OUTCOME_UNKNOWN`, porque o pedido pode ter
   chegado. Uma ferramenta que devolve `Failure` explicitamente sabe o que aconteceu, e aí o status é
   `FAILED`.
6. **Métricas com ferramentas inventadas:** o label `tool` usa `unknown` para nomes que não estão no catálogo.
   Assim, nomes inventados pelo LLM não criam séries novas no Prometheus.
7. **Bugs encontrados pelos testes antes do commit:**
   - o limite de tamanho dos argumentos não interpretáveis era aplicado **antes** do mascaramento, e
     `<redacted>` é mais longo que o valor original. Agora o limite é aplicado de novo depois da limpeza;
   - o `ToolRegistry` tinha dois construtores, e o Spring não sabia qual usar. O construtor do Spring agora
     tem `@Autowired`.
8. **SpotBugs (5 achados):**
   - `CT_CONSTRUCTOR_THROW` (2): corrigido tornando o `ToolRegistry` `final`. O construtor lança exceção por
     desenho, e uma subclasse poderia capturar o objeto parcialmente construído;
   - `CRLF_INJECTION_LOGS` (3): suprimido no método de tratamento de falhas, porque os valores logados são
     nomes de ferramenta validados contra `^[a-z][a-zA-Z0-9]{2,63}$`, e os logs são JSON.
9. **Limitação honesta dos timeouts:** Java não mata threads. O executor interrompe a thread virtual e segue
   em frente, mas uma ferramenta que ignore a interrupção continua rodando em segundo plano. Os adapters reais
   (fatia 3) precisam de timeouts no cliente HTTP menores que o da ferramenta (documento 05, §6).
10. **Recuperação na inicialização** (`RUNNING` → `OUTCOME_UNKNOWN` após um crash) fica na fatia 4, junto com a
    recuperação das execuções do agente, como já estava no plano.
