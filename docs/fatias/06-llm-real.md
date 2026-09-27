# Fatia 6 — LLM real (OpenAI)

> Status: **implementada** (2026-09-27), com a demonstração no modelo real **pendente na máquina do autor**.
> Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 6.

## Objetivo

Provar que o `LlmGateway` isola o provedor e que o agente é útil com um modelo real, ainda só lendo. Ficam de
fora: vários provedores, roteamento de modelos, streaming e qualquer escrita no Docker (fatia 8).

## Uma ressalva que atravessa a fatia inteira

A documentação oficial da OpenAI (`platform.openai.com`, `developers.openai.com`) e a própria API
(`api.openai.com`) **não eram alcançáveis** do ambiente em que desenvolvi. O formato da Responses API foi lido
em fontes de terceiros e fixado pelos testes com um stub. Por isso:

- o formato é uma **hipótese testada contra o stub**, não contra a OpenAI;
- **a primeira execução real, na sua máquina, é a validação**. O roteiro está abaixo;
- se algo divergir (nome de campo, formato de erro), o ajuste fica no `OpenAiLlmAdapter` e no `OpenAiStub`, sem
  tocar no agente.

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | Cliente HTTP próprio, sem SDK e sem Spring AI |
| 2 | Responses API com `store: false`; histórico reconstruído dos registros; sem `previous_response_id` |
| 3 | `strict: false` nas ferramentas (a validação estrita é do backend) |
| 4 | Até 2 retentativas em 429/5xx, respeitando `Retry-After` dentro do timeout; 4xx sem retentativa |
| 5 | Modelo e preços obrigatórios e sem padrão; falha na subida se faltarem |
| 6 | Orçamento diário checado ao aceitar a mensagem (`429`) e antes de cada volta (`BUDGET_EXCEEDED`/`DAILY_BUDGET`) |
| 7 | A demonstração com o modelo real e a medição de H2 ficam com o autor; daqui, tudo é provado contra o stub |

## Estrutura

```
integration/openai/  OpenAiLlmAdapter (Responses API), OpenAiProperties, OpenAiConfiguration (só com provider=openai)
llm/                 LlmResponse + estimatedCostUsd, LlmException.REJECTED, LlmProperties (provedor válido, orçamento)
agent/               DailyBudget, custo em llm_call e agent_execution, métrica devops.llm.cost.usd
scripts/             evaluate-agent.sh (primeira medição de H2)
```

O adapter fica em `integration`, ao lado do adapter do Docker, porque é ali que ficam os clientes HTTP (regra do
ArchUnit). Uma regra nova garante que ele só conhece o port do LLM: não vê ferramentas, política, banco nem o
agente.

## Tradução

| Tipo neutro | Responses API |
|---|---|
| `systemPrompt` | `instructions` |
| `User` / `Assistant(texto)` | `{role: "user" \| "assistant", content}` |
| `Assistant(propostas)` | `{type: "function_call", call_id, name, arguments}` |
| `ToolResult` | `{type: "function_call_output", call_id, output}` |
| `LlmToolSpec` | `{type: "function", name, description, parameters, strict: false}` |
| resposta `function_call` | `LlmToolCall` |
| `output_text` / `refusal` | o texto da resposta (uma recusa também é mostrada) |
| `status: completed` | `STOP` ou `TOOL_CALLS` |
| `status: incomplete` + `max_output_tokens` | `LENGTH` (a API marca a resposta como parcial) |
| `status: failed` | `UNAVAILABLE` |
| outro `incomplete`, JSON inválido, `function_call` sem `call_id` | `INVALID_RESPONSE` |
| itens `reasoning` | ignorados |

| HTTP | Categoria | Retentativa |
|---|---|---|
| 429 | `RATE_LIMITED` | até 2, respeitando `Retry-After` (teto de 10 s) |
| 5xx, 408, falha de conexão | `UNAVAILABLE` | até 2, backoff exponencial a partir de 500 ms |
| outros 4xx (400, 401, 403…) | `REJECTED` | não |
| timeout | `TIMEOUT` | não (o prazo acabou) |

O corpo de erro da OpenAI **nunca** é lido para mensagens nem logs, porque pode ecoar o prompt.

## Custo e orçamento

- **Por chamada:** `input_tokens × preço de entrada + output_tokens × preço de saída` (por milhão, 6 casas),
  gravado em `llm_call.estimated_cost_usd` e somado em `agent_execution`. O desconto de tokens em cache é
  ignorado, então a estimativa tende a ficar acima do real. *Não confirmei como a OpenAI cobra os tokens de
  raciocínio; pelo que sei, entram como saída.*
- **Orçamento diário por organização (dia UTC):** somado das `llm_call`.
  - Ao aceitar uma mensagem: estourado → `429` com `Retry-After` até a meia-noite UTC.
  - Antes de cada volta: estourado → `BUDGET_EXCEEDED` / `DAILY_BUDGET`.
- **Métricas:** `devops.llm.cost.usd{provider}`, `devops.llm.retries{provider}`, além das da fatia 4.

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| O provedor fica isolado | O adapter só traduz; o agente não muda | O mesmo loop, política e ferramentas rodam com o stub em `OpenAiAgentIT`; ArchUnit `openai_adapter_only_knows_the_llm_port` |
| Sem estado no provedor | `store: false`, sem `previous_response_id`, histórico completo | `OpenAiLlmAdapterTest.theRequest_isStateless_…` |
| A chave não vaza (TM-B3-02) | Só no header `Authorization`; `toString()` mascarado; mensagens de erro sem corpo | `theApiKey_neverAppearsInLogsOrErrors` (logs capturados em 401, 429, 500 e JSON inválido) |
| Nenhum segredo do sistema vai ao provedor (TM-B3-01) | Por construção (fatia 4) | `OpenAiAgentIT.theRequestsSentToTheProvider_neverContainTheSystemsSecrets`: o corpo gravado não tem a chave, o segredo do JWT, as senhas nem o token do usuário |
| Falhas limpas (TM-B3-04) | Categorias + retentativas limitadas | 429 com `Retry-After` e depois sucesso; 429 e 503 persistentes (3 tentativas); 400/401/403 (1 tentativa); timeout; provedor inalcançável; e `aProviderOutage_failsTheExecution_withAClearReason` |
| Custo registrado (RNF-CUS-01) | Tabela de preços da configuração | `functionCalls_becomeProposals_…` (0,0036 USD por chamada) e `theOpenAiProvider_drivesTheSameLoop_andTheCostIsRecorded` (0,0072 USD na execução) |
| Orçamento diário (RNF-CUS-02, TM-B3-05) | `DailyBudget` | `anExhaustedDailyBudget_refusesNewMessages` (429 + `Retry-After`), `theDailyBudget_stopsARunningExecution_betweenTurns` |
| Configuração incompleta não sobe | Validação nas propriedades e na configuração | `OpenAiConfigurationTest`, `missingModelKeyOrPrices_stopTheApplication`, e na demonstração abaixo |

## Testes

`./mvnw verify` → backend: **160** unitários/arquitetura + **121** de integração; demo-api: **2**. No total,
**283** testes (eram 261), **0 achados** do SpotBugs + FindSecBugs. **Nenhum teste chama a OpenAI.**

## Demonstração (neste ambiente)

| Verificação | Resultado |
|---|---|
| `scripts/evaluate-agent.sh` com o `scripted` | Os 5 cenários rodaram de ponta a ponta; os achados do backend foram `UNHEALTHY`, `EXITED_WITH_ERROR` (42), `EXITED_WITH_ERROR` (3), `KILLED_BY_SIGKILL`, `STOPPED` |
| `LLM_PROVIDER=openai` sem `LLM_DAILY_BUDGET_USD` | O backend não sobe: `LLM_PROVIDER=openai requires LLM_DAILY_BUDGET_USD (devops.llm.daily-budget-usd)` |
| `LLM_PROVIDER=openai` completo, com chave falsa | O backend sobe; a pergunta termina `FAILED` / `LLM_UNAVAILABLE` depois das retentativas (a OpenAI não é alcançável daqui); `llm_call` com `ERROR`/`UNAVAILABLE`; **0** ocorrências da chave nos logs |

## Roteiro com o modelo real (na sua máquina)

1. No `.env`: `LLM_PROVIDER=openai`, `OPENAI_API_KEY`, `LLM_MODEL` (o nome exato do modelo), os dois preços por
   milhão de tokens da página de preços e um `LLM_DAILY_BUDGET_USD` baixo (por exemplo, `1`). Recomendo também
   um limite de gastos no painel da OpenAI.
2. `docker compose up -d --build`.
3. **Validação do formato:** uma pergunta simples ("o demo-api está de pé?") e `GET /api/v1/executions/{id}`.
   Se vier `FAILED` com `LLM_REJECTED`, o provedor recusou a requisição: é o sinal de que algum campo do formato
   diverge. Me mande o `status_reason` e eu ajusto o adapter (o corpo do erro não é logado de propósito; para
   investigar, repita a requisição à mão com `curl`).
4. **Primeira medição de H2:** `./scripts/evaluate-agent.sh`. Ele quebra o `demo-api` de 5 formas, pergunta
   "Por que minha API está fora do ar?" e grava uma tabela em `evaluations/`, com a resposta, os achados e o
   custo. Preencha a coluna "Causa correta?". Não há meta ainda (documento 01, H2).

## Divergências e achados

1. **A documentação oficial e a API não eram alcançáveis daqui** (acima). É a principal limitação da fatia.
2. **Categoria nova, `REJECTED`,** para 4xx sem retentativa (chave inválida, requisição inválida). A execução
   termina com `LLM_REJECTED`, que distingue "o provedor recusou" de "o provedor caiu".
3. **O orçamento diário é obrigatório com `openai`.** O desenho previa checá-lo; torná-lo obrigatório segue a
   mesma lógica da decisão 5 (sem limite de gasto implícito).
4. **HTTP puro só para `localhost`:** a URL base precisa ser `https`, exceto para um stub local, para a chave
   nunca trafegar sem TLS.
5. **O adapter usa o `HttpClient` do JDK direto** (o do Docker usa o `RestClient`), para ter o timeout exato por
   requisição dentro do prazo da chamada.
6. **Propostas com nomes inventados voltam no histórico.** Um `function_call` com um nome que não está em
   `tools` (por exemplo, `deleteContainer`, negado pela política) é reenviado na volta seguinte. *Não sei se a
   OpenAI aceita isso;* se recusar, aparece como `LLM_REJECTED` no roteiro acima.
7. **Reasoning items não voltam no histórico.** Com `store: false`, os itens de raciocínio de modelos de
   raciocínio não são reenviados. *Pelo que sei, isso funciona, mas pode reduzir a qualidade entre voltas;*
   a medição de H2 vai mostrar.
8. **O roteiro `demo-status` do `scripted` passou a reconhecer "fora do ar" e "is … down"**, para o script de
   avaliação rodar também sem API key.
9. **SpotBugs:** um achado (`UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR` no custo da execução), corrigido
   inicializando o campo com zero.
10. **Cenário de crash loop fora da avaliação:** o `demo-api` não tem restart policy (decisão da fatia 5), então
    o `RESTART_LOOP` continua provado só no `RealDockerIT`.
