# Fatia 5 — Diagnóstico determinístico

> Status: **implementada** (2026-09-27). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 5.

## Objetivo

Provar que regras objetivas resolvem o que não precisa de IA e que podem ser testadas por uma tabela de casos.

```
dado observado (inspect) ─► regra determinística ─► finding + evidência ─► LLM (como fato)
```

O modelo não precisa "decidir" se `exitCode = 137` significa OOM: ele recebe o diagnóstico e as evidências. E um
sinal ambíguo continua ambíguo:

```
OOMKilled = true                  ─► OOM_KILLED
exitCode = 137 + OOMKilled = false ─► KILLED_BY_SIGKILL ("não conclusivo")
```

**Fica de fora:** regras baseadas em logs, métricas ou histórico de deploy (pós-MVP), e o uso dos achados na
tela de aprovação (fatia 7).

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | Pacote `tool/diagnostics`: classe pura, `Clock` injetado, limites configuráveis (`restart-loop-threshold: 3`, `recently-started-window: 60s`) |
| 2 | Exit code 143 → `STOPPED`, severidade MEDIUM |
| 3 | `UNHEALTHY` e `NO_HEALTHCHECK` só com o container `RUNNING` |
| 4 | `CONTAINER_NOT_FOUND` só na listagem; `getContainerStatus` continua `FAILED`/`TARGET_NOT_FOUND` |
| 5 | Métrica genérica `devops.tool.findings{tool, code, severity}`, no executor |
| 6 | Prompt `agent-system-v2`: achados são fatos calculados pelo backend; logs continuam dados não confiáveis |
| 7 | OOM real e restart loop só nos testes com Docker real; o `demo-api` continua sem restart policy |

## Fatos verificados no Docker 29.3.1 antes do desenho

| Cenário | `State` |
|---|---|
| Saiu com 3 e foi iniciado de novo | `running`, `ExitCode=0`: o código é zerado |
| `docker stop` num processo que ignora SIGTERM | `exited`, 137, `OOMKilled=false` |
| `docker stop` na JVM do `demo-api` | `exited`, **143** |
| `docker kill` | `exited`, 137, `OOMKilled=false` |
| 16 MB de limite + `tail /dev/zero` | `exited`, 137, **`OOMKilled=true`** |
| `--restart on-failure` + processo que falha | `restarting`, `RestartCount=6` |

## As regras

A tabela está no [documento 05, §8.3](../05-contratos-das-ferramentas.md), com os ajustes desta fatia. Em
resumo:

| Código | Severidade | Observação |
|---|---|---|
| `CONTAINER_NOT_FOUND` | HIGH | Só na listagem |
| `OOM_KILLED` | HIGH | `OOMKilled = true`, e nada mais sobre o 137 |
| `KILLED_BY_SIGKILL` | HIGH | 137 sem OOM: a mensagem cita OOM, `docker kill` e `docker stop` além do prazo, e diz que **não é conclusivo** |
| `EXITED_WITH_ERROR` | HIGH | Parado com código diferente de 0, 137 e 143 |
| `UNHEALTHY` | HIGH | Só rodando |
| `RESTART_LOOP` | MEDIUM | `RESTARTING` ou `RestartCount ≥ 3` |
| `STOPPED` | MEDIUM | Código 0 ou 143 |
| `RECENTLY_STARTED` | INFO | Rodando há menos de 60 s |
| `NO_HEALTHCHECK` | INFO | Só rodando |

Cada achado leva um `evidence` com os dados que o dispararam (`service`, `state`, `health`, `exitCode`,
`oomKilled`, `restartCount`, `startedAt`). Os achados vêm ordenados por severidade e depois por código.

## Onde os achados aparecem

- **`getContainerStatus`:** o campo `findings` da saída (`{data, findings}`, formato da fatia 2).
- **`listContainers`:** uma lista única no topo da saída, com o nome lógico em `evidence.service`.
- **LLM:** dentro do resultado da ferramenta, depois da limpeza do executor. O prompt `agent-system-v2` diz para
  tratá-los como fatos e para não transformar em certeza um achado que se declara não conclusivo. As execuções
  anteriores continuam registradas com `agent-system-v1`.
- **Métrica:** `devops.tool.findings{tool, code, severity}`. Os códigos são constantes do código, então a
  cardinalidade é limitada.

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| Regras testáveis por tabela | `ContainerDiagnostics`, pura, com `Clock` fixo nos testes | `ContainerDiagnosticsTest`: 18 linhas de tabela + 2 testes (mensagem do 137 e ordem) |
| 137 ambíguo não vira OOM | 137 com OOM → só `OOM_KILLED`; sem OOM → só `KILLED_BY_SIGKILL`, com "not conclusive" | Tabela; e com containers reais (abaixo) |
| Health antigo não engana | `UNHEALTHY`/`NO_HEALTHCHECK` só com `RUNNING` | Tabela ("stopped after being unhealthy") e a demonstração: containers parados continuaram com `health=UNHEALTHY` sem gerar o achado |
| Pureza | Nada de Docker, HTTP, banco ou agente | ArchUnit `diagnostics_are_pure` |
| Regras com containers reais, pelo proxy | Containers criados pelo cliente Docker do Testcontainers e lidos por `getContainerStatus` | `RealDockerIT.realContainers_produceTheExpectedFindings`: OOM do kernel → `OOM_KILLED`; `docker kill` → `KILLED_BY_SIGKILL`; `exit 3` → `EXITED_WITH_ERROR`; SIGTERM → `STOPPED`; restart policy → `RESTART_LOOP` |
| Achados na saída, com evidência e métrica | `ToolResult.success(data, findings)`; contagem no executor | `ContainerToolsIT` (137 → `KILLED_BY_SIGKILL` com evidência e métrica; listagem com `CONTAINER_NOT_FOUND` do serviço sem container) |
| Achados chegam ao LLM | Resultado da ferramenta + prompt v2 | `AgentIT.aQuestion_runsTheLoop_andEverythingIsRecorded` (o resultado enviado ao LLM contém `findings` e `RECENTLY_STARTED`; o prompt contém a regra) |

## Testes

`./mvnw verify` → backend: **143** unitários/arquitetura + **116** de integração; demo-api: **2**. No total,
**261** testes (eram 237), **0 achados** do SpotBugs + FindSecBugs.

## Demonstração

Com `docker compose up`, cada ação seguida da pergunta "is demo-api up?" ao agente (provedor `scripted`, que
mostra o resultado da ferramenta):

| Ação | O que o agente recebeu de `getContainerStatus` |
|---|---|
| `demo-api` recém-iniciado | `RUNNING`, `HEALTHY` → `RECENTLY_STARTED` (INFO) |
| `/chaos/unhealthy` | `RUNNING`, `UNHEALTHY` → `UNHEALTHY` (HIGH), `RECENTLY_STARTED` (INFO) |
| `/chaos/crash?code=42` | `EXITED`, exitCode 42 → `EXITED_WITH_ERROR` (HIGH) |
| `/chaos/hang`, com o container rodando há mais de 60 s | `RUNNING`, `UNHEALTHY` → `UNHEALTHY` (HIGH) |
| `/chaos/oom` | `EXITED`, exitCode 3, `OOMKilled=false` → `EXITED_WITH_ERROR` (HIGH): a limitação da JVM já documentada |
| `docker kill devops-demo-api` | `EXITED`, 137, `OOMKilled=false` → `KILLED_BY_SIGKILL` (HIGH, não conclusivo) |
| `docker stop devops-demo-api` | `EXITED`, 143 → `STOPPED` (MEDIUM) |

A métrica no Prometheus, depois da demonstração:

```
devops_tool_findings_total{code="EXITED_WITH_ERROR",severity="HIGH",tool="getContainerStatus"} 2.0
devops_tool_findings_total{code="KILLED_BY_SIGKILL",severity="HIGH",tool="getContainerStatus"} 1.0
devops_tool_findings_total{code="RECENTLY_STARTED",severity="INFO",tool="getContainerStatus"} 2.0
devops_tool_findings_total{code="STOPPED",severity="MEDIUM",tool="getContainerStatus"} 1.0
devops_tool_findings_total{code="UNHEALTHY",severity="HIGH",tool="getContainerStatus"} 2.0
```

## Divergências e achados

1. **Nenhuma divergência do desenho aprovado.**
2. **O Testcontainers não foi usado para subir os containers "que morrem"**: eles foram criados pelo cliente
   Docker do próprio Testcontainers (`DockerClientFactory`), porque a espera de "container rodando" do
   `GenericContainer` não combina com containers que saem ou reiniciam sozinhos. Eles são removidos no `finally`
   do teste.
3. **O `demo-api` parado mostra `health=UNHEALTHY`** mesmo depois de um `docker kill` ou `docker stop` feitos
   com ele saudável. Não verifiquei a causa exata; o mais provável é uma sonda que falhou durante a parada. A
   decisão 3 evitou um falso `UNHEALTHY` nesses casos, e a demonstração mostrou isso.
4. **SpotBugs: nenhum achado novo** nesta fatia.
