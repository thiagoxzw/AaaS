# Fatia 6 — LLM real (OpenAI)

> Status: **implementada** (2026-09-27) e **validada com o modelo real** na máquina do autor (2026-09-28), com a
> primeira medição de H2. Ajustes da validação no PR seguinte (abaixo).
> Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 6.

## Objetivo

Provar que o `LlmGateway` isola o provedor e que o agente é útil com um modelo real, ainda só lendo. Ficam de
fora: vários provedores, roteamento de modelos, streaming e qualquer escrita no Docker (fatia 8).

## Uma ressalva que atravessa a fatia inteira

A documentação oficial da OpenAI (`platform.openai.com`, `developers.openai.com`) e a própria API
(`api.openai.com`) **não eram alcançáveis** do ambiente em que desenvolvi. O formato da Responses API foi lido
em fontes de terceiros e fixado pelos testes com um stub. Por isso:

- o formato era uma **hipótese testada contra o stub**, não contra a OpenAI;
- **a primeira execução real, na máquina do autor, foi a validação**. O roteiro está abaixo;
- se algo divergisse (nome de campo, formato de erro), o ajuste ficaria no `OpenAiLlmAdapter` e no `OpenAiStub`,
  sem tocar no agente.

**Resultado:** o formato das requisições e respostas foi confirmado sem nenhum ajuste. A única divergência
apareceu no formato de um erro: uma conta sem crédito responde 429, e o adapter a tratava como limite de
requisições ([Validação com o modelo real](#validação-com-o-modelo-real)).

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | Cliente HTTP próprio, sem SDK e sem Spring AI |
| 2 | Responses API com `store: false`; histórico reconstruído dos registros; sem `previous_response_id` |
| 3 | `strict: false` nas ferramentas (a validação estrita é do backend) |
| 4 | Até 2 retentativas em 429/5xx, respeitando `Retry-After` dentro do timeout; 4xx sem retentativa. *Ajuste aprovado depois da validação:* um 429 de conta sem crédito também não é retentado |
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
| 429 com `error.type = insufficient_quota` (conta sem crédito) | `QUOTA_EXHAUSTED` | não (só volta com crédito) |
| outros 429 | `RATE_LIMITED` | até 2, respeitando `Retry-After` (teto de 10 s) |
| 5xx, 408, falha de conexão | `UNAVAILABLE` | até 2, backoff exponencial a partir de 500 ms |
| outros 4xx (400, 401, 403…) | `REJECTED` | não |
| timeout | `TIMEOUT` | não (o prazo acabou) |

O corpo de erro da OpenAI **nunca** vai para mensagens nem logs, porque pode ecoar o prompt. O único campo lido
é o `error.type` de um 429, comparado com um valor fixo.

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
| Conta sem crédito não é retentada | Só o `error.type` do 429 é lido | `a429WithoutCredit_isQuotaExhausted_andNeverRetried` (1 tentativa; outros 429 e corpos que não são JSON continuam retentados) e `OpenAiAgentIT.anAccountWithoutCredit_failsAtOnce_withItsOwnReason` (`LLM_QUOTA_EXHAUSTED`) |
| Custo registrado (RNF-CUS-01) | Tabela de preços da configuração | `functionCalls_becomeProposals_…` (0,0036 USD por chamada) e `theOpenAiProvider_drivesTheSameLoop_andTheCostIsRecorded` (0,0072 USD na execução) |
| Orçamento diário (RNF-CUS-02, TM-B3-05) | `DailyBudget` | `anExhaustedDailyBudget_refusesNewMessages` (429 + `Retry-After`), `theDailyBudget_stopsARunningExecution_betweenTurns` |
| Configuração incompleta não sobe | Validação nas propriedades e na configuração | `OpenAiConfigurationTest`, `missingModelKeyOrPrices_stopTheApplication`, e na demonstração abaixo |

## Testes

`./mvnw verify` → backend: **160** unitários/arquitetura + **121** de integração; demo-api: **2**. No total,
**283** testes (eram 261), **0 achados** do SpotBugs + FindSecBugs. **Nenhum teste chama a OpenAI.**

Depois dos ajustes da validação: backend **162** + **122**, demo-api **2** (**286**), 0 achados.

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
   - `LLM_REJECTED`: o provedor recusou a requisição, sinal de que algum campo do formato diverge.
   - `LLM_QUOTA_EXHAUSTED`: a conta está sem crédito.
   - Para investigar, repita a requisição à mão com `curl`, porque o corpo do erro não é logado de propósito.
   - No Windows, veja antes os cuidados do README (Git Bash, CRLF do `jq`, acentos).
4. **Primeira medição de H2:** `./scripts/evaluate-agent.sh`. Ele quebra o `demo-api` de 5 formas, pergunta
   "Por que minha API está fora do ar?" e grava uma tabela em `evaluations/`, com a resposta, os achados e o
   custo. Preencha a coluna "Causa correta?". Não há meta ainda (documento 01, H2).

## Validação com o modelo real

Feita pelo autor em 2026-09-28, no Windows (Docker Desktop com WSL2, Git Bash), com o modelo `gpt-5.6-luna`.

### O formato

A pergunta "o demo-api está de pé?" terminou `COMPLETED`, sem nenhum ajuste no adapter:

| O que ficou provado | Evidência |
|---|---|
| Requisição e resposta da Responses API | Nenhum `LLM_REJECTED` |
| Ferramentas | O modelo propôs `getContainerStatus`; o backend validou e executou (`READ_ONLY`, `SUCCEEDED`) |
| Histórico reenviado a cada volta | 2 voltas; a segunda recebeu o `function_call_output` e respondeu com os dados reais |
| Custo | 1.427 tokens de entrada + 83 de saída = US$ 0,000385 (com os preços do `.env`) |
| Proxy do Docker no Windows | O backend leu o `demo-api` pelo `docker-socket-proxy` no Docker Desktop |

A primeira tentativa, antes de a conta ter crédito, mostrou o formato real desse erro:

```json
{"error": {"message": "You have no credits remaining. ...", "type": "insufficient_quota",
           "param": null, "code": "credit_balance_exhausted"}}
```

O adapter tratava esse 429 como limite de requisições: retentava 2 vezes (cerca de 7 s) e terminava em
`LLM_RATE_LIMITED`, escondendo a causa. Agora ele termina de imediato em `LLM_QUOTA_EXHAUSTED` (decisão 4
ajustada, com aprovação).

### Primeira medição de H2

`scripts/evaluate-agent.sh`, pergunta "Por que minha API esta fora do ar?", sem acento por causa do problema do
Windows descrito abaixo. A coluna "Causa correta?" é a avaliação do autor, em três estados, **sem transformar 5
casos numa taxa de acerto**.

| Cenário | Achados do backend | O que o agente concluiu | Custo (USD) | Causa correta? |
|---|---|---|---|---|
| unhealthy | `UNHEALTHY` | Processo rodando e healthcheck forçado para DOWN (citou o log do caos) | 0,001006 | ✅ Sim |
| crash | `EXITED_WITH_ERROR` | Saiu com código 42, sem OOM, com a linha exata do log | 0,001031 | ✅ Sim |
| oom | `EXITED_WITH_ERROR` | A JVM esgotou o heap, **não** foi um OOM do kernel (`oomKilled: false`). Deslize: disse que o container "reiniciou uma vez" | 0,001382 | ✅ Sim |
| kill | `KILLED_BY_SIGKILL` (não conclusivo) | "Esgotamento do heap", com base nos logs da execução anterior | 0,001730 | ❌ Não |
| stop | `STOPPED` | Parada graciosa por `SIGTERM` (certo), mas apontou o OOM anterior como "causa imediata" | 0,002029 | 🟡 Parcial |

Total: cerca de US$ 0,0072 nas 5 execuções. Todas usaram `getContainerStatus` e `getContainerLogs`.

**O que a medição mostrou:** os dois erros vêm de **dado de entrada, não de raciocínio**.

- O Docker devolve os logs de **todas as execuções** do container. Como o script religa o mesmo container entre
  os cenários, o modelo recebeu o OOM antigo junto com os logs atuais e o tratou como atual. O custo crescente
  por cenário (de 0,0010 a 0,0020) acompanha o acúmulo desses logs.
- Toda resposta sobre um container parado repete `Health: UNHEALTHY`, o valor antigo que o Docker mantém. Os
  achados já ignoram esse valor (fatia 5, decisão 3), mas o dado bruto ainda chega ao modelo.

A correção é no backend, não no prompt. Ela muda o contrato das ferramentas (logs a partir do último start e
`health` só com o container rodando), então vai ter desenho próprio antes da fatia 7. Depois dela, os **mesmos
5 cenários** serão medidos de novo, para a comparação valer.

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
11. **Conta sem crédito responde 429 (validação real).** O formato é `type: insufficient_quota`,
    `code: credit_balance_exhausted`: eu esperava o valor no campo `code`. A categoria nova, `QUOTA_EXHAUSTED`,
    lê só o `error.type`.
12. **O Windows quebrou o script de avaliação de três formas (validação real), e nenhuma era do backend:**
    - o `jq` termina as linhas com CRLF, e o `\r` entrava nos ids e nas URLs;
    - o `curl.exe` recebe os argumentos na página de código do Windows, então o `á` saía como o byte `e1` e o
      backend recusava o JSON com `400`, corretamente;
    - senhas com `(`, `&` ou `$` quebravam o `. ./.env`.

    O script agora remove o `\r`, manda os corpos pela entrada padrão, lê o `.env` sem executá-lo e, quando o
    backend recusa algo, **mostra o status e o `detail`** em vez de parar calado. A versão antiga parava sem
    mensagem, porque usava `curl -sf`. Testado com um `jq` que imprime CRLF, um `curl` que recusa argumentos
    não ASCII e um `.env` com CRLF e caracteres de shell: a versão antiga falha, e a nova roda os 5 cenários.
13. **Primeira medição de H2:** 3 sim, 1 parcial, 1 não. Os erros vêm dos logs de execuções anteriores (acima).
