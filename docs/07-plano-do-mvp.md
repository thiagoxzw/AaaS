# 07 — Plano do MVP

> Status: **aceito** (2026-09-26). Este documento transforma os documentos 01 a 06 numa sequência de
> entregas. Ele não repete as decisões; ele as referencia.

## 1. Regras do plano

1. **Fatias verticais.** Cada fatia atravessa todas as camadas necessárias (API → domínio → banco →
   integração) para entregar **uma capacidade completa**. Não existe uma "fatia das entidades" nem uma
   "fatia dos controllers".
2. **Toda fatia produz algo executável e testável.** Ao final dela, dá para subir o sistema, demonstrar a
   capacidade nova com um roteiro curto e ver o CI verde.
3. **Toda fatia prova uma parte da arquitetura.** A tabela da seção 3 diz qual.
4. **Nenhuma fatia começa antes de a anterior cumprir a definição de pronto** (seção 4).
5. **O projeto roda sem API key.** Um provedor de LLM `scripted` permite a qualquer pessoa clonar o
   repositório, rodar `docker compose up` e ver o fluxo completo. A OpenAI é opcional (fatia 6). Isso é
   importante para quem avaliar o portfólio.

### Como cada fatia é conduzida

```
1. Desenho      explico o que será construído, as classes principais, as decisões e as alternativas
2. Validação    você aprova (ou ajusta) antes de qualquer código
3. Código       implementação + testes, em commits pequenos
4. Execução     rodo o roteiro de demonstração e os testes, e mostro a saída
5. Revisão      revisão do diff (correção, segurança, simplicidade)
6. Registro     documentação e ADRs atualizadas, commit e push
```

## 2. Visão geral das fatias

```
Fatia 0  Esqueleto executável
   ↓
Fatia 1  Autenticação + Ambientes + Auditoria
   ↓
Fatia 2  Framework de ferramentas + Fake Runtime
   ↓
Fatia 3  Docker real: proxy + adapter + demo-api + list/inspect/logs
   ↓
Fatia 4  Agente + ScriptedLlmGateway
   ↓
Fatia 5  Diagnóstico determinístico
   ↓
Fatia 6  LLM real (OpenAI adapter)                   ← acrescentada
   ↓
Fatia 7  Aprovação
   ↓
Fatia 8  Restart + verificação
   ↓
Fatia 9  Fechamento do MVP (demo, README, H1/H2, release)   ← acrescentada
```

**Os dois acréscimos em relação à sequência proposta:**

- **Fatia 6 (LLM real):** sem ela, o MVP só funcionaria com o LLM roteirizado. Ela vem **depois** do
  diagnóstico de propósito: o modelo real já recebe achados determinísticos, e isso será a primeira
  demonstração real de "por que minha API está fora do ar?", ainda só de leitura.
- **Fatia 9 (fechamento):** a validação das hipóteses H1 e H2, o roteiro de demo reproduzível, o README
  completo, as varreduras de segurança no CI e a tag `v0.1.0`. Sem ela, "o MVP está pronto" seria uma
  opinião.

## 3. As fatias em detalhe

### Fatia 0 — Esqueleto executável

**Prova:** o projeto sobe, é testável, e o CI funciona desde o primeiro commit de código.

| | |
|---|---|
| **Entra** | Estrutura do repositório (seção 5), aplicação Spring Boot com Actuator (`/actuator/health`, `/actuator/prometheus`), logs JSON, Flyway com a migração inicial (`organization`), `docker-compose.yml` com backend + PostgreSQL + Prometheus + Grafana (portas em `127.0.0.1`, senhas via `.env`), Dockerfile multi-stage não-root, CI (build + testes + imagem) e varredura de segredos, base do Testcontainers e do ArchUnit, `.env.example` |
| **Fica de fora** | Qualquer endpoint de negócio |
| **Decisões a tomar** | Versões de Java e Spring Boot (verificadas na documentação atual), a ferramenta de build (seção 7) e a imagem base |
| **Demonstração** | `docker compose up` → `curl localhost:8080/actuator/health` → `UP`. O Prometheus coleta o backend, e o Grafana abre com o dashboard básico (JVM/HTTP). |
| **Critérios de aceite** | CI verde num PR. `docker compose up` funciona a partir de um clone limpo, só copiando `.env.example` para `.env`. A imagem roda como não-root (RNF-OPS-03). Nenhuma porta pública fora de `127.0.0.1` (RNF-SEG-15). |
| **Testes** | Teste de contexto com PostgreSQL via Testcontainers. O Flyway aplica as migrações. A regra ArchUnit inicial (dependências entre módulos, documento 03 §12). |

### Fatia 1 — Autenticação + Ambientes + Auditoria

**Prova:** identidade, autorização por permissão, isolamento na consulta e auditoria imutável.

| | |
|---|---|
| **Entra** | `app_user`, `user_role`, seed da organização e do admin (a senha vem do `.env`), `POST /auth/login` → JWT, filtro que **recarrega o usuário a cada requisição** (RNF-SEG-13), `PermissionResolver`, limitação de tentativas de login (RNF-SEG-17), CRUD de `environment` e da allowlist `environment_service`, `audit_event` com trigger append-only, `problem+json` |
| **Fica de fora** | API keys, múltiplas organizações, refresh token |
| **Pontos provados por teste de integração** (documento 04 §10) | UUIDv7 gerado pela aplicação, FK composta + JPA (inclusive a **rejeição pelo banco** de uma referência a outra organização), `jsonb` (em `audit_event.details`) |
| **Demonstração** | Login → criar o ambiente `local` → registrar o serviço `demo-api` → consultar a auditoria e ver `ENVIRONMENT_CREATED` e `SERVICE_ALLOWLISTED` |
| **Critérios de aceite** | RF-01..03, RF-10, RF-11, RF-13, RF-45 (auditoria dessas ações), RNF-SEG-08/09/13/14/17 |
| **Testes** | Matriz de autorização (endpoints × papéis), `404` para recurso de outra organização, usuário desativado com token válido é recusado, trigger impede `UPDATE`/`DELETE`, login com a mesma resposta para usuário inexistente e senha errada |

### Fatia 2 — Framework de ferramentas + Fake Runtime

**Prova:** a cadeia `Registry → Policy → Executor → Tool` funciona **sem Docker e sem LLM**, e as
invariantes de segurança existem antes de qualquer ferramenta real.

| | |
|---|---|
| **Entra** | `Tool`, `ToolDefinition`, `ToolInput`, `ToolResult`, `ToolExecutionContext`, `ToolRegistry` com validação na inicialização (documento 05 §3.1), `PolicyEngine` (cadeia de validação e matriz risco × autonomia), `TargetResolver` + `ContainerRef`, `ToolExecutor` (validação de argumentos, timeout, retentativa só para read-only, `Redactor`, sanitização de ANSI e caracteres de controle, truncagem, gravação `RUNNING` antes da chamada), port `ContainerRuntime`, `FakeContainerRuntime`, tabelas `agent_execution` e `tool_execution` (o executor precisa delas; nesta fatia, os testes criam as execuções diretamente), `GET /tools` (catálogo filtrado por usuário e ambiente), métricas de ferramentas |
| **Fica de fora** | O adapter Docker real e o LLM |
| **Demonstração** | `GET /api/v1/tools?environmentId=…` com um `OPERATOR` e com um `VIEWER`: catálogos diferentes. Os testes do executor rodando. |
| **Critérios de aceite** | RF-30, RF-40, RF-49 (parte da política), RNF-SEG-02, RNF-SEG-07b, RNF-SEG-11, RNF-CONF-04..07, RNF-MAN-02 |
| **Testes** | Invariantes do registry (a aplicação não sobe com uma definição inválida), cada negação da cadeia com o seu `denial_reason`, `OUTCOME_UNKNOWN` para ferramenta não idempotente, sem retentativa para `HIGH_RISK`, mascaramento e sanitização. Uma ferramenta de teste (`FakeHighRiskTool`) exercita os caminhos de risco. |

### Fatia 3 — Docker real: proxy + adapter + demo-api + list/inspect/logs

**Prova:** o isolamento do Docker funciona na prática, e as ferramentas do domínio operam um runtime real
sem conhecer o Docker.

| | |
|---|---|
| **Entra** | `docker-socket-proxy` no Compose (rede interna, sem porta publicada, apenas os endpoints necessários, imagem fixada por digest), `DockerEngineContainerRuntime` (mapeamento **apenas dos campos do domínio**, demultiplexação dos logs, timeouts), `demo-api` com endpoints de caos, as ferramentas `listContainers`, `getContainerStatus` e `getContainerLogs`, `POST /environments/{id}/connectivity-check` (RF-12) e `GET /environments/{id}/services/status` (estado ao vivo dos serviços da allowlist, sem logs) |
| **Fica de fora** | O diagnóstico (fatia 5) e o restart |
| **Pontos a verificar** | Configuração do proxy no Docker Desktop + WSL2, filtro por label no proxy (TM-B6-04), semântica do `RestartCount`, formato dos logs multiplexados |
| **Demonstração** | Subir tudo → `connectivity-check` → `services/status` mostra o `demo-api` `RUNNING`/`HEALTHY` → chamar o caos → `services/status` mostra `UNHEALTHY` ou `EXITED` |
| **Critérios de aceite** | RF-11 (containers fora da allowlist são invisíveis), RF-12, RF-31, RNF-SEG-03, RNF-SEG-10, RNF-SEG-12 |
| **Testes** | O adapter contra WireMock com fixtures gravadas (inspect **com variável de ambiente secreta**, logs multiplexados, 404, 403, resposta lenta). Um teste com Docker real + proxy real no CI: `exec` e `create` retornam `403`. As ferramentas com o fake. A allowlist nunca é violada. |

**Nota sobre a `demo-api`:** é uma aplicação pequena, num módulo próprio, com endpoints como
`/chaos/unhealthy`, `/chaos/crash` (sai com código ≠ 0), `/chaos/hang` e `/chaos/log?msg=…` (grava uma
linha controlada no log, para os cenários de prompt injection). *Provocar um `OOMKilled` real com uma JVM
pode ser difícil, porque ela tende a lançar `OutOfMemoryError` antes de o kernel matar o processo. Vou
testar; se não for confiável, esse cenário fica só nos testes com o fake.*

**Resultado da fatia 3** ([detalhes](fatias/03-docker-real.md)):
- proxy trocado para o `linuxserver/socket-proxy` ([ADR-011](adr/0011-linuxserver-socket-proxy.md)), porque o
  `tecnativa/docker-socket-proxy` não libera o restart sem liberar `create` e `start`;
- o proxy **não** filtra por nome nem por label (TM-B6-04 confirmado como risco residual);
- o `RestartCount` não conta restarts manuais;
- os logs sem TTY chegam multiplexados (`application/vnd.docker.multiplexed-stream`), e com TTY em texto puro;
- o `/chaos/oom` sai com código 3 e `OOMKilled=false`: o `OOMKilled` real fica só nos testes com o fake;
- o Docker Desktop com WSL2 ainda precisa ser conferido na máquina do usuário.

### Fatia 4 — Agente + ScriptedLlmGateway

**Prova:** o loop do agente é controlado pelo backend, limitado, persistido, idempotente e testável sem
LLM real.

| | |
|---|---|
| **Entra** | `conversation`, `message`, `llm_call`, `LlmGateway` com tipos neutros, `ScriptedLlmGateway` (roteiros em arquivo, usado nos testes **e** como provedor `scripted` para rodar localmente sem API key), orchestrator (máquina de estados, orçamentos, `context_snapshot`), `ContextBuilder`, `ExecutionDispatcher` in-process com fila limitada, `POST /conversations`, `POST /conversations/{id}/messages` (`202` + `Idempotency-Key`), `GET /executions/{id}` (linha do tempo e `actions[]` projetadas dos registros), cancelamento, recuperação na inicialização (`INTERRUPTED`, `OUTCOME_UNKNOWN`) e métricas do agente e do LLM |
| **Fica de fora** | O LLM real e a aprovação. Nenhuma ferramenta de produção exige aprovação antes da fatia 8, então o caminho de aprovação só é exercitado a partir da fatia 7. |
| **Demonstração** | Com `LLM_PROVIDER=scripted`: enviar "o demo-api está de pé?" → `202` → consultar a execução → ver `getContainerStatus` executado no Docker real e a resposta final |
| **Critérios de aceite** | RF-20..27, RF-44, RF-46, RNF-CONF-01..03, 08..10, RNF-MAN-03, as invariantes 2 e 3 do documento 04. **Migração obrigatória (pendência da fatia 2):** `tool_execution.agent_execution_id` e `tool_execution.llm_call_id` são obrigatórios no domínio, mas ainda não têm FK no banco, porque `agent_execution` e `llm_call` só nascem nesta fatia. **A fatia 4 só é concluída quando uma migração adicionar as FKs compostas** `(organization_id, agent_execution_id) → agent_execution(organization_id, id)` e `(organization_id, llm_call_id) → llm_call(organization_id, id)`, com um teste de integração provando que o banco rejeita uma referência inválida. |
| **Testes** | A primeira parte da suíte do "LLM malicioso" (documento 06 §6: S1, S2, S4, S5, S7, S8 e S9), orçamento esgotado, execução ativa duplicada → `409`, a mesma `Idempotency-Key` devolve a mesma execução, a lista de ações vem dos registros, e não do texto |

**Resultado da fatia 4** ([detalhes](fatias/04-agente.md)): o loop, os orçamentos (contados **antes** da
política), a idempotência, o cancelamento cooperativo, a recuperação na inicialização e as FKs obrigatórias da
`tool_execution` (migração V7) estão implementados. O S4 foi adaptado (documento 06 §6), e o S9 roda de ponta a
ponta com Docker real.

### Fatia 5 — Diagnóstico determinístico

**Prova:** as regras objetivas resolvem o que não precisa de IA, e são testáveis por tabela de casos.

| | |
|---|---|
| **Entra** | `ContainerDiagnostics` (regras do documento 05 §8.3), os `findings` na saída de `getContainerStatus` e `listContainers`, os achados incluídos no contexto do LLM e uma métrica de achados por código |
| **Fica de fora** | Regras baseadas em logs, métricas ou histórico de deploy (depois do MVP) |
| **Demonstração** | Cada endpoint de caos da `demo-api` → `getContainerStatus` retorna o achado esperado |
| **Critérios de aceite** | RF-33 |
| **Testes** | Tabela de casos cobrindo cada regra e as combinações ambíguas (por exemplo, `137` sem `OOMKilled` → `KILLED_BY_SIGKILL`, sem concluir que foi OOM) |

**Resultado da fatia 5** ([detalhes](fatias/05-diagnostico.md)): `ContainerDiagnostics` é uma classe pura
testada por tabela; os achados saem em `getContainerStatus` e `listContainers`, chegam ao LLM junto com o
resultado da ferramenta (prompt `agent-system-v2`) e são contados em `devops.tool.findings`. `OOM_KILLED`,
`KILLED_BY_SIGKILL`, `EXITED_WITH_ERROR`, `STOPPED` e `RESTART_LOOP` são provados também com containers reais.

### Fatia 6 — LLM real (OpenAI adapter)

**Prova:** o `LlmGateway` isola o provedor, e o agente é útil com um modelo real, ainda só lendo.

| | |
|---|---|
| **Entra** | `OpenAiLlmAdapter` (a escolha entre SDK, Spring AI com execução automática de ferramentas **desligada** ou cliente HTTP próprio, e qual API da OpenAI usar, feita após verificar a documentação atual), modelo e tabela de preços em configuração, custo estimado por chamada e por execução, orçamento diário (RNF-CUS-02), prompt de sistema versionado (`prompt_version`) |
| **Fica de fora** | Vários provedores e roteamento de modelos |
| **Demonstração** | Com `LLM_PROVIDER=openai`: quebrar a `demo-api` → "por que minha API está fora do ar?" → o agente consulta o status e os logs, cita os achados e explica. O custo da execução aparece no `GET /executions/{id}`. |
| **Critérios de aceite** | ADR-010, RNF-CUS-01/02, TM-B3-01/02/04/05, e a primeira medição de H2 (sem meta ainda) |
| **Testes** | O adapter contra WireMock (tradução de formatos, tool calls, 429/5xx, timeout), a API key nunca aparece em logs, a requisição ao LLM nunca contém os segredos configurados. **Nenhum teste automatizado chama a OpenAI de verdade**: a avaliação com o modelo real é manual ou num job separado e opcional. |

**Resultado da fatia 6** ([detalhes](fatias/06-llm-real.md)): `OpenAiLlmAdapter` com cliente HTTP próprio
sobre a Responses API sem estado, custo por chamada e por execução, orçamento diário (429 ao aceitar,
`BUDGET_EXCEEDED`/`DAILY_BUDGET` entre voltas) e `scripts/evaluate-agent.sh` para a primeira medição de H2. A
documentação oficial da OpenAI não era alcançável do ambiente de desenvolvimento: a demonstração com o modelo
real e a medição de H2 ficaram para a máquina do autor.

**Validação com o modelo real (2026-09-28):** o formato da Responses API foi confirmado sem ajustes. A
validação mudou três coisas: a categoria `QUOTA_EXHAUSTED` (conta sem crédito, sem retentativa), o
`evaluate-agent.sh` robusto no Windows e o registro da **primeira medição de H2: 3 sim, 1 parcial, 1 não**. Os
dois erros vêm dos logs de execuções anteriores do mesmo container, que o backend entrega como se fossem atuais.
A correção muda o contrato das ferramentas, por isso vai ter desenho próprio antes da fatia 7, seguido de uma
nova medição com os mesmos 5 cenários ([detalhes](fatias/06-llm-real.md#validação-com-o-modelo-real)).

### Fatia 6.1 — Dados atuais para o diagnóstico (acrescentada)

**Prova:** o agente recebe evidência da execução atual, e não histórico apresentado como atual.

**Resultado** ([detalhes](fatias/06-1-dados-atuais.md)): `getContainerLogs` lê por padrão só a execução atual
(`since` = `StartedAt`, com nanossegundos), um `since` explícito alcança as anteriores, `health` vira
`NOT_APPLICABLE` fora de `RUNNING` e o achado `RESTART_LOOP` diz como ver as execuções anteriores. O prompt não
mudou.

**Segunda medição de H2** (2 rodadas, mesmos 5 cenários): `unhealthy`, `crash`, `oom` e `stop` ficaram Sim nas
duas rodadas, e `kill` ficou Parcial (identifica SIGKILL sem inventar causa, mas não chega ao `docker kill`). Na
primeira medição foram 3 Sim, 1 Parcial e 1 Não. Toda leitura de logs sem `since` recebeu só a execução atual.
A melhora vem da combinação do filtro padrão com a nova descrição da ferramenta: o modelo pediu `since` em 4 de
5 chamadas na primeira medição e em 1 de 10 na segunda. Por isso, ela não pode ser atribuída só ao filtro
([detalhes](fatias/06-1-dados-atuais.md#segunda-medição-de-h2)).

### Fatia 7 — Aprovação

**Prova:** a aprovação humana é uma máquina de estados estruturada, segura contra replay, TOCTOU e
concorrência.

| | |
|---|---|
| **Entra** | `approval`, `WAITING_APPROVAL` na execução e na chamada, `GET /approvals?status=PENDING`, `GET /approvals/{id}` (contrato `system` × `agentClaims`, documento 05 §13), `POST /approvals/{id}/decision`, expiração (um job que marca `EXPIRED`), a retomada da execução com a chamada **exata**, a reavaliação da política ao executar e a auditoria das decisões |
| **Fica de fora** | Quatro olhos (V5) e notificações (V2+) |
| **Demonstração** | Com o provedor `scripted` e a ferramenta de teste de risco num perfil de teste: a execução pausa, a aprovação aparece, é aprovada, e a execução retoma. **A demonstração com uma ferramenta real acontece na fatia 8.** |
| **Critérios de aceite** | RF-41..43, RF-48, RNF-SEG-06, RNF-CONF-08, TM-B7-01..04/06/07 |
| **Testes** | S3, S6, S10 e S11 da suíte do "LLM malicioso", aprovação reutilizada, hash divergente, aprovação expirada, aprovador sem permissão, decisão e auditoria atômicas, a execução em `WAITING_APPROVAL` sobrevive a um restart do backend |

**Resultado da fatia 7** ([detalhes](fatias/07-aprovacao.md)): módulo `approval` com a máquina de estados do
ADR-0006, criada na mesma transação da chamada; decisão de uso único com auditoria atômica; retomada que executa
só a chamada gravada, depois de comparar os hashes e reavaliar a política com o estado atual; expiração e
retomada por uma varredura idempotente, guiada pelo estado gravado. S3, S6, S10 e S11 provados pela API HTTP em
`ApprovalIT`. A demonstração com a ferramenta real fica para a fatia 8, como planejado.

### Fatia 8 — Restart + verificação

**Prova:** o fluxo completo de ponta a ponta: diagnóstico → proposta → aprovação → ação → verificação →
auditoria.

| | |
|---|---|
| **Entra** | `restartContainer` (documento 05 §8.5), com a verificação determinística pós-ação, o `impactDescription`, o `reason` obrigatório, o endpoint de restart liberado no proxy (`ALLOW_RESTARTS=1`, que também libera `stop` e `kill`: risco residual da ADR-011) e as consultas de auditoria por recurso (RF-45) |
| **Demonstração** | **O roteiro do documento 01 §5, completo**, com a OpenAI **e** com o provedor `scripted` |
| **Critérios de aceite** | RF-32, RF-45, RF-46, os critérios do roteiro do documento 01 §5 e H3 |
| **Testes** | Restart sem retentativa, `OUTCOME_UNKNOWN` quando a conexão cai após o pedido, verificação `UNHEALTHY` sem `FAILED`, e um teste de ponta a ponta com o fake + scripted + aprovação. O proxy real permite o restart e continua bloqueando `exec`/`create`. |

**Resultado da fatia 8** ([detalhes](fatias/08-restart.md)): `restartContainer` com a verificação dentro da
ferramenta (só conta como reiniciado se o `StartedAt` mudou), `SUCCEEDED` + `RESTART_UNVERIFIED` quando o serviço
não volta saudável, e `OUTCOME_UNKNOWN` sem retentativa quando a conexão cai depois do pedido. O proxy libera o
restart (`ALLOW_RESTARTS=1`), e o CI prova que `start`/`create`/`exec` continuam `403` (`stop`/`kill` passam: risco
residual da ADR-011). Auditoria com os filtros `toolName`, `agentExecutionId` e `toolExecutionId` (RF-45) e
`GET /tool-executions/{id}`, que responde quem pediu, o que foi observado, por quê, quem aprovou, quando e o
resultado (RF-46, H3). **O roteiro do documento 01 §5 rodou completo no compose com o `scripted`**. A rodada com
a OpenAI fica para a sua máquina, sem forçar o modelo a propor o restart.

### Fatia 9 — Fechamento do MVP

**Prova:** as hipóteses do documento 01 foram testadas, e o projeto é apresentável.

| | |
|---|---|
| **Entra** | A suíte completa do "LLM malicioso" rodando no CI (**H1**), a primeira medição de **H2** com os cenários de caos e o modelo real (resultado documentado, qualquer que seja), o dashboard do Grafana para o agente (ferramentas, aprovações, negações, LLM, custo), varredura de dependências e de imagens no CI, README completo (problema, solução, arquitetura, como executar, exemplos, screenshots, API, segurança, decisões, limitações, roadmap), roteiro de demo reproduzível e a tag `v0.1.0` |
| **Critérios de aceite** | H1: zero ações não autorizadas. H2: medido e documentado. H3: as perguntas "quem" e "por quê" respondidas pela API. Todas as fatias cumprem a definição de pronto. |

**Resultado da fatia 9a — evidências** ([detalhes](fatias/09a-evidencias.md)): as três máquinas de estado
extraídas do código e testadas como tabelas (40, 20 e 110 casos); a matriz ameaça → mitigação → código → teste →
evidência, com os nomes de teste do documento 06 corrigidos (cerca de 30 eram nomes planejados que não existiam);
a bateria A–F do restart; `OUTCOME_UNKNOWN` sobrevivendo a recuperação e varredura com exatamente 1 restart; as
corridas aprovação × cancelamento × expiração; injeção de prompt nos logs e nos argumentos; canários de segredo
no log, HTTP, banco e LLM. Três achados foram corrigidos por decisão do autor: argumentos que o mascaramento
alteraria são recusados antes da aprovação (9a-01), o adapter do Docker não registra valores recebidos (9a-02)
e `ToolExecution` guarda as suas próprias transições (9a-03). 9b (observabilidade e caos) e 9c (release) vêm
depois, em PRs separados.

**Resultado da fatia 9b — observabilidade e caos** ([detalhes](fatias/09b-observabilidade-caos.md)): quatro
métricas (`devops.approvals`, `devops.approval.wait`, `devops.tool.restart.verification` e a sua duração),
contadas depois do commit, e o dashboard *Agente*, ligado ao código por um teste que exige cada série que ele
consulta. Cinco cenários de caos observados no compose, com o `docker events` como evidência independente. Nos
três em que a conexão caiu com um restart em andamento (`kill -9`, restart gracioso e proxy caindo), o Docker
concluiu o restart, o sistema registrou `OUTCOME_UNKNOWN` e não houve segundo pedido. O `OUTCOME_UNKNOWN`
atribuído pela recuperação passou a contar na mesma métrica (O-9b-1). O desligamento gracioso que não espera as
chamadas em andamento ficou registrado como limitação conhecida (O-9b-2).

**Fatia 9c — reprodutibilidade e release, em revisão** ([detalhes](fatias/09c-release.md)): `scripts/demo.sh`
(o roteiro canônico em 9 passos), dados brutos em `scripts/evaluate-agent.sh` (commit, modelo, versão do prompt,
argumentos e escopo de cada chamada, decisões e estado final), a varredura das imagens com o Trivy no CI, só como
relatório, e o README reescrito. A varredura encontrou 3 CVEs críticos no Tomcat embutido (9c-01), que aguardam
decisão junto com a política de bloqueio. Depois: a medição final de H2, a revisão final e a tag `v0.1.0`.

## 4. Definição de pronto (vale para toda fatia)

- [ ] Desenho explicado e validado **antes** do código.
- [ ] Código seguindo o `CONTRIBUTING` (inglês, Conventional Commits, migrações Flyway).
- [ ] Testes da fatia escritos e passando. Os testes citam os IDs dos requisitos e ameaças que cobrem.
- [ ] CI verde.
- [ ] Regras de arquitetura (ArchUnit) passando: nenhuma fronteira de módulo violada.
- [ ] O roteiro de demonstração da fatia executado de verdade, com a saída mostrada.
- [ ] Nenhum segredo no código, nos logs ou nas fixtures (a varredura de segredos passa).
- [ ] Revisão do diff feita (correção, segurança, simplicidade).
- [ ] Documentação atualizada: README (seção "status"), documentos afetados e uma ADR nova se houve
      decisão relevante.
- [ ] O que ficou para depois está registrado (não some em silêncio).

**Sem meta numérica de cobertura.** Uma meta de porcentagem incentiva testes que exercitam código sem
verificar comportamento. O relatório de cobertura é gerado e olhado, mas o critério é: **cada requisito e
cada ameaça da fatia têm um teste nomeado.**

## 5. Estrutura do repositório (proposta)

```
/
├── backend/                  aplicação devops-agent (Spring Boot)
│   └── src/main/java/…/      módulos: identity, environment, conversation, agent, llm,
│                             tool, integration, audit, shared (documento 03 §12)
├── demo-api/                 aplicação alvo com endpoints de caos
├── observability/
│   ├── prometheus/           prometheus.yml
│   └── grafana/              provisioning de datasources e dashboards
├── docs/                     documentos 01–07, ADRs e roteiro de demo
├── .github/workflows/        CI
├── docker-compose.yml
├── .env.example
├── CONTRIBUTING.md
└── README.md
```

## 6. Observabilidade ao longo das fatias

A observabilidade é construída **junto** com cada capacidade, e não numa fatia própria no fim:

| Fatia | Métricas e sinais adicionados |
|---|---|
| 0 | JVM, HTTP, health, logs JSON com `requestId` (o `traceId` do OpenTelemetry fica para a V3) |
| 1 | Logins (sucesso e falha), negações de autorização |
| 2 | Execuções de ferramenta por nome, status e risco, duração, negações por motivo, truncagens e mascaramentos |
| 3 | Chamadas ao runtime por operação e resultado, erros `RUNTIME_FORBIDDEN` |
| 4 | Execuções por estado, iterações e orçamentos esgotados, fila do dispatcher |
| 5 | Achados por código |
| 6 | Chamadas ao LLM (latência, tokens, erros, custo estimado) |
| 7 | Aprovações pedidas, aprovadas, rejeitadas e expiradas, e tempo até a decisão |
| 9 | Dashboard consolidado do agente no Grafana |

## 7. Decisões da fatia 0

**Decididas (2026-09-26):** Maven como ferramenta de build; branch `main` criada a partir da documentação
aceita; **um PR por fatia**, com destino à `main`.

Contexto original das decisões:

1. **Ferramenta de build: Maven ou Gradle?** Recomendo **Maven**: é o mais comum em vagas de backend Java,
   é declarativo e simples de explicar. O Gradle (Kotlin DSL) é mais flexível e mais rápido em builds
   grandes, o que não é o caso aqui.
2. **Versões de Java e Spring Boot:** verificadas na documentação oficial no início da fatia.
3. **Fluxo de branches e PRs:** hoje o repositório só tem a branch `claude/eager-rubin-snonv5`, sem uma
   `main`. Proposta: criar a `main` a partir da documentação aceita e fazer **um PR por fatia**, com o CI
   rodando em cada PR. Cada fatia fica revisável isoladamente, e o histórico conta a história do projeto.
