# Fatia 6.1 — Dados atuais para o diagnóstico

> Status: **implementada e medida** (2026-09-28). Origem: a primeira medição de H2
> ([fatia 6](06-llm-real.md#primeira-medição-de-h2)). Resultado: [segunda medição de H2](#segunda-medição-de-h2).

## Objetivo

O agente recebe evidência da **execução atual** do container, e não histórico apresentado como se fosse atual.

Na primeira medição de H2, os dois erros (`kill` e `stop`) vieram do dado de entrada, não do raciocínio:

- o Docker devolve os logs de **todas as execuções** do container, e o modelo leu o OOM de uma execução
  anterior como a causa atual;
- o Docker mantém o último health de um container parado, e todas as respostas repetiram `Health: UNHEALTHY`.

**Fica de fora:** mudança no prompt, ferramentas novas e regras baseadas em logs.

**Critério de sucesso:** o agente não recebe logs de execuções anteriores quando chama `getContainerLogs` sem
`since`. Na nova medição de H2, a comparação principal é nos cenários `kill` e `stop`.

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | `getContainerLogs` lê, por padrão, só a execução atual: `since` igual ao `StartedAt`, com os nanossegundos (um *inspect* antes dos logs, já permitido no proxy) |
| 2 | Um `since` explícito substitui o padrão e alcança as execuções anteriores. A saída ganha `scope` (`CURRENT_RUN`, `SINCE` ou `ALL_RUNS`) e `runStartedAt` |
| 3 | Um container que nunca iniciou (`StartedAt` zerado) é lido sem filtro (`ALL_RUNS`) |
| 4 | `health` só vale com o container `RUNNING`. Nos outros estados, a saída diz `NOT_APPLICABLE`, em `getContainerStatus`, `listContainers` e `GET /services/status`. O `ContainerSnapshot` do adapter continua com o valor bruto, usado pelas regras de diagnóstico |
| 5 | A mensagem do achado `RESTART_LOOP` diz que os logs das execuções anteriores só vêm com `since` |
| 6 | O prompt continua `agent-system-v2`, para a única variável da nova medição ser o dado. As descrições das ferramentas mudam, porque fazem parte do contrato |
| 7 | Nova medição de H2 na máquina do autor: os mesmos 5 cenários, o mesmo modelo, a mesma pergunta sem acento e, se possível, 2 rodadas |

## Fatos verificados no Docker 29.3.1 antes do desenho

| Pergunta | Resultado |
|---|---|
| O `GET /logs` separa as execuções? | **Não:** um container que rodou duas vezes devolveu as linhas das duas |
| `since` com o `StartedAt` fracionário (`1790564099.231776520`) | Devolveu exatamente as linhas da execução atual |
| `since` com segundos inteiros | Funciona quando os starts estão em segundos diferentes; um restart no mesmo segundo vazaria linhas da execução anterior. Por isso os nanossegundos |
| `tail` junto com `since` | O `tail` é aplicado depois do `since`: `tail=2` trouxe as 2 últimas linhas da execução atual |

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| Sem logs de execuções anteriores por padrão | `GetContainerLogsTool` faz um *inspect* e passa o `StartedAt` como `since` | `ContainerToolsIT.getContainerLogs_readsOnlyTheCurrentRun_unlessSinceIsGiven` (uma linha 1 ns antes do start fica de fora); `RealDockerIT.realLogs_defaultToTheCurrentRun_andAStoppedContainersHealthIsNotApplicable` (container real que rodou 2 vezes, lido pelo proxy: o padrão traz só a segunda execução) |
| Precisão de nanossegundos | `LogQuery` passou a levar um `Instant`; o adapter envia `segundos.nanossegundos` | `DockerEngineContainerRuntimeTest.logs_sinceIsSentAsUnixTime_withTheNanoseconds` |
| `since` explícito continua valendo | Substitui o padrão; `scope = SINCE` | Mesmos testes (a janela de 24 h traz as 4 linhas; o real, as 2 execuções) |
| Container nunca iniciado | Sem filtro; `scope = ALL_RUNS` | `getContainerLogs_ofANeverStartedContainer_readsEverything` |
| `health` só com o container rodando | `HealthStatus.reported(state, health)` nas três saídas e na `evidence` dos achados | `ContainerToolsIT.health_isNotApplicable_unlessTheContainerIsRunning`, `EnvironmentRuntimeIT` (API humana) e o teste real: o Docker mantém `unhealthy` no container parado, e a ferramenta mostra `NOT_APPLICABLE` |
| A `evidence` dos achados não repete o health antigo | As regras leem o valor bruto; a `evidence` mostra o valor reportado | `ContainerDiagnosticsTest.theEvidence_neverShowsTheStaleHealthOfAStoppedContainer` |
| `RESTART_LOOP` aponta para as execuções anteriores | Texto na mensagem do achado | `ContainerDiagnosticsTest.aRestartLoop_pointsToTheLogsOfEarlierRuns` |
| Prompt inalterado | Nenhuma mudança em `SystemPrompt` | `promptVersion` continua `agent-system-v2` |

## Testes

`./mvnw verify` → backend: **164** unitários/arquitetura + **126** de integração; demo-api: **2**. No total,
**292** testes (eram 286), **0 achados** do SpotBugs + FindSecBugs.

## Demonstração

Com `docker compose up` e o provedor `scripted`, repeti a sequência do cenário `kill` da avaliação (`/chaos/oom`,
`docker start`, `docker kill`) com a imagem de antes e a de depois. Depois perguntei ao agente "is demo-api up?"
e "show me the logs":

| | Antes (fatia 6) | Depois (fatia 6.1) |
|---|---|---|
| `getContainerStatus` | `EXITED`, `health: UNHEALTHY`, e o mesmo `UNHEALTHY` na `evidence` do achado | `EXITED`, `health: NOT_APPLICABLE`, também na `evidence` |
| `getContainerLogs` | Sem `scope` | `scope: CURRENT_RUN` |

**O que essa demonstração não mostra:** o roteiro `scripted` pede só `tail: 20`, e as 20 últimas linhas já eram
todas da execução atual, antes e depois. O efeito nos logs está provado pelo `RealDockerIT`, com um container
real que roda duas vezes, lido pelo proxy. O efeito no agente de verdade é o que a nova medição de H2 vai
mostrar: na primeira, o modelo chamou `getContainerLogs` sem `tail` (200 linhas) e recebeu o OOM antigo.

## Nova medição de H2 (na máquina do autor)

Depois do merge, com o `.env` do modelo real:

```bash
git pull
docker compose up -d --build
QUESTION="Por que minha API esta fora do ar?" ./scripts/evaluate-agent.sh   # rodada 1
QUESTION="Por que minha API esta fora do ar?" ./scripts/evaluate-agent.sh   # rodada 2
```

A pergunta continua **sem acento**, igual à primeira medição, para a comparação valer (o script já aceita
acentos). O custo esperado é de cerca de 1,5 centavo de dólar nas duas rodadas.

## Segunda medição de H2

Feita pelo autor em 2026-09-28, em duas rodadas seguidas (03:16 e 03:17 UTC), com o mesmo modelo
(`gpt-5.6-luna`), a mesma pergunta sem acento e os mesmos 5 cenários da primeira.

### Critério de avaliação

A coluna "Causa correta?" é do autor, em três estados, **sem transformar os casos numa taxa de acerto**:

- **Sim:** a causa principal está certa. Um deslize periférico fica anotado, mas não rebaixa a nota. Foi o
  critério usado na primeira medição (o `oom`, com o "reiniciou uma vez" inventado, ficou Sim), e ele não mudou.
- **Parcial:** fatos corretos, mas a causa operacional real não foi alcançada, ou uma causa errada aparece como
  principal.
- **Não:** a causa apontada está errada.

### Resultado

| Cenário | 1ª medição | 2ª, rodada 1 | 2ª, rodada 2 |
|---|---|---|---|
| unhealthy | ✅ Sim | ✅ Sim | ✅ Sim |
| crash | ✅ Sim | ✅ Sim | ✅ Sim |
| oom | ✅ Sim (deslize: "reiniciou uma vez") | ✅ Sim | ✅ Sim |
| kill | ❌ Não | 🟡 Parcial | 🟡 Parcial |
| stop | 🟡 Parcial | ✅ Sim | ✅ Sim |

**`kill`, Parcial nas duas rodadas:** o agente identificou SIGKILL (137, sem OOM) e não inventou uma causa. Ele
repetiu que o achado não é conclusivo, mas não chegou à causa operacional real, o `docker kill`. É melhor que a
primeira medição, que concluiu "esgotamento do heap" a partir de logs antigos, mas ainda não é uma identificação
completa. Os dados que o backend entrega hoje não distinguem um `docker kill` de um `docker stop` que estourou
o prazo, e essa limitação é do diagnóstico, não do modelo.

**Custo:** cerca de US$ 0,0058 na rodada 1 e US$ 0,0047 na rodada 2, contra US$ 0,0072 na primeira medição. Ele
ficou estável entre os cenários (cerca de 0,0009 a 0,0010), em vez de crescer a cada cenário (0,0010 a 0,0020). A
exceção é o `unhealthy` da rodada 1 (0,0019), que pediu histórico (abaixo).

### Quando o modelo pediu `since`

Os argumentos de cada `getContainerLogs`, lidos em `tool_execution` depois das rodadas:

| Medição | Chamadas | Com `since` explícito | `scope` |
|---|---|---|---|
| 1ª (fatia 6, sem filtro padrão) | 5 | **4** (`"2h"`: unhealthy, crash, oom, kill) | Não existia |
| 2ª, rodada 1 | 5 | **1** (`"2h"`: unhealthy) | 1 `SINCE`, 4 `CURRENT_RUN` |
| 2ª, rodada 2 | 5 | **0** | 5 `CURRENT_RUN` |

Três coisas aparecem aqui:

1. **Toda chamada sem `since` recebeu só a execução atual** (9 de 9). É o critério de sucesso da fatia.
2. **O modelo passou a pedir histórico muito menos:** 4 de 5 chamadas na primeira medição, 1 de 10 na segunda.
   O `kill` da primeira medição, o erro principal, **tinha pedido `since: "2h"`**. Portanto, o filtro padrão
   sozinho não teria mudado aquela chamada.
3. **Quando pediu histórico, o modelo o separou do presente:** no `unhealthy` da rodada 1, ele citou o OOM como
   "de uma execução anterior" e manteve a causa atual certa. A saída tinha `scope: SINCE` e `runStartedAt`.

O `stop` é o único caso que mostra o efeito **isolado** do novo padrão. Na primeira medição, ele não pediu
`since` e recebeu o histórico, porque ainda não havia filtro. Na segunda, também sem `since`, recebeu
`CURRENT_RUN`.

### O que se pode concluir, e o que não

A fatia 6.1 reduziu a exposição inadvertida a histórico e também mudou a forma como o modelo escolhe consultar
`getContainerLogs`. A melhora vem da **combinação** de três coisas:

- o padrão `CURRENT_RUN`, que garante o critério quando o modelo não pede histórico;
- a **nova descrição da ferramenta** ("By default only the current run…; pass since to include earlier runs"),
  que muito provavelmente fez o modelo pedir `since` com muito menos frequência;
- o `scope` e o `runStartedAt`, que deixaram o histórico identificável quando ele foi pedido.

**Não dá para atribuir a melhora só ao filtro padrão.** A decisão 6 manteve o prompt, mas as descrições das
ferramentas mudaram, e elas tiveram um peso que eu não previ no desenho. Separar os dois efeitos exigiria uma
medição com o filtro e a descrição antiga, e ela não foi feita. Também são 15 casos no total, com um modelo que
não é determinístico: o resultado mostra uma direção, não uma taxa.

## Divergências e achados

1. **Nenhuma divergência do desenho aprovado.**
2. **Limitação conhecida:** num restart loop, a execução atual pode ainda não ter a linha do erro. O agente
   depende do achado `RESTART_LOOP`, que agora diz como ver as execuções anteriores, e de pedir `since`. Isso não
   é resolvido pelo prompt, de propósito.
3. **Uma chamada a mais por leitura de logs:** o *inspect* antes dos logs. Se o container reiniciar entre as duas
   chamadas, a leitura inclui a execução que acabou de terminar. Não tratei esse caso, porque a janela é de
   milissegundos e o efeito é só trazer linhas a mais.
4. **A `evidence` dos achados também repetia o health antigo.** A demonstração mostrou `UNHEALTHY` na `evidence`
   de um container parado, mesmo com o campo `health` já corrigido. A `evidence` chega ao modelo, então ela
   passou a mostrar o valor reportado. As regras continuam lendo o valor bruto. Isso está dentro da decisão 4,
   porque é a mesma saída da ferramenta.
5. **O `date` do busybox não tem `%N`:** o primeiro teste real imprimia o mesmo texto nas duas execuções. Ele
   passou a usar um UUID por execução, o que também deixou a prova mais forte.
6. **A descrição da ferramenta mudou o comportamento do modelo mais do que o previsto.** Na primeira medição, o
   modelo pediu `since: "2h"` em 4 de 5 chamadas. Na segunda, pediu em 1 de 10. O desenho isolou o prompt
   (decisão 6), mas não as descrições, e por isso a comparação não separa o efeito do filtro do efeito do texto
   da ferramenta (acima).
7. **`kill` continua Parcial:** o backend não tem dado para distinguir um `docker kill` de um `docker stop` que
   estourou o prazo. Fica registrado como limitação do diagnóstico, sem proposta nesta fatia.
