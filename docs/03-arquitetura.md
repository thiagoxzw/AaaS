# 03 — Arquitetura de alto nível

> Status: **aceito** (2026-09-26). Esta é a visão de alto nível. O detalhamento de cada parte (modelo de dados,
> contratos das ferramentas, threat model, plano do MVP) vem nos próximos documentos (seção 14).

## 1. Estilo arquitetural: monólito modular

O backend é **uma única aplicação Spring Boot**, organizada em **módulos com fronteiras explícitas**.
Não há microsserviços. Veja a [ADR-001](adr/0001-monolito-modular.md).

**Por quê:**

- Existe um único desenvolvedor e um único domínio coeso. Microsserviços trariam rede, consistência
  distribuída, deploy múltiplo e observabilidade distribuída **sem nenhum benefício** nesta fase.
- Um monólito **bem modularizado** é o que a maioria das empresas realmente precisa. Saber justificar
  isso numa entrevista vale mais do que ter 6 serviços.
- Se no futuro for preciso escalar o processamento de execuções separadamente, o módulo `agent` já tem
  uma fronteira clara e pode virar um *worker* consumindo do RabbitMQ (V2+), sem reescrever o domínio.

## 2. Três planos e fronteiras de confiança

### 2.1 Raciocínio × controle × execução

Esta é a ideia central do projeto: **o LLM tem capacidade de raciocínio, mas nenhuma autoridade.**

```
┌───────────────────────────┐
│   PLANO DE RACIOCÍNIO     │   LLM (não confiável)
│                           │
│ interpreta a intenção     │
│ propõe ferramentas        │
│ analisa os resultados     │
│ redige a resposta         │
└─────────────┬─────────────┘
              │  proposta = { tool, arguments }   ← é um PEDIDO, não uma ordem
              ▼
┌───────────────────────────┐
│    PLANO DE CONTROLE      │   backend (código determinístico, confiável)
│                           │
│ valida a ferramenta       │
│ valida os parâmetros      │
│ checa a allowlist         │
│ checa permissão e         │
│   nível de autonomia      │
│ aplica os orçamentos      │
│ exige aprovação humana    │
│ executa e audita          │
└─────────────┬─────────────┘
              │  chamada tipada e autorizada
              ▼
┌───────────────────────────┐
│    PLANO DE EXECUÇÃO      │   adapters → sistemas externos
│                           │
│ Docker (via proxy)        │
│ GitHub / CI/CD (V2)       │
│ Prometheus (V3)           │
└───────────────────────────┘
```

> **Pergunta de entrevista:** "E se o LLM decidir fazer algo perigoso?"
>
> **Resposta:** "O LLM não tem autoridade para executar nada. Ele apenas *pede* uma ferramenta. A
> autorização acontece fora do modelo, em código determinístico: se a ferramenta não existe, se o
> container não está na allowlist, se o usuário não tem permissão ou se o nível de autonomia não permite,
> a chamada é negada e registrada. Se a ação é de risco, ela espera uma aprovação humana estruturada."

### 2.2 O agente nunca tem mais poder que o usuário

O agente age **em nome** do usuário que iniciou a execução. A permissão efetiva de uma chamada é a
**interseção** de três coisas:

```
permissão efetiva = permissões do usuário solicitante
                  ∩ nível de autonomia do ambiente
                  ∩ ferramentas e recursos permitidos no ambiente (allowlist)
```

Isso evita o problema clássico do **confused deputy**: um usuário com permissão só de leitura não
consegue usar o agente, que "tem acesso ao Docker", para reiniciar um container.

### 2.3 Fronteiras de confiança

| Elemento | Nível de confiança | Tratamento |
|---|---|---|
| Código do backend e configuração de política | **Confiável** (é a base de confiança do sistema) | Revisado, testado, versionado |
| PostgreSQL | **Confiável** | Acessado só pelo backend, com credenciais vindas do ambiente |
| Usuário autenticado | **Autenticado, não "confiável"** | Toda ação é checada contra os papéis e permissões dele |
| Texto da mensagem do usuário | **Não confiável como instrução ao sistema** | Vai para o LLM, nunca para a política |
| Saída do LLM | **Não confiável** | Uma proposta é uma *requisição*, validada como qualquer entrada externa |
| Saída das ferramentas (logs, respostas de API, texto de issues) | **Não confiável** | Truncada, mascarada e marcada como dado; **nunca** altera controles (RF-49) |
| Provedor de LLM | **Terceiro** | Recebe só o necessário. O que é enviado sai da sua máquina, e isso é documentado |
| docker-socket-proxy | **Confiável para restringir a superfície** | Defesa em profundidade, independente da política do backend |
| Docker Engine e containers alvo | **Operados, não confiáveis como fonte de instrução** | — |

```
              NÃO CONFIÁVEL                 ║             CONFIÁVEL (plano de controle)
                                            ║
  texto do usuário ─────────────────────────╫──► API (autenticação + autorização)
  saída do LLM (propostas) ─────────────────╫──► Registry → Policy → Approval → Executor
  saída das ferramentas (logs, APIs) ───────╫──► sanitização / truncagem ──► vira DADO para o LLM
                                            ║
                                  fronteira de confiança
```

### 2.4 Cadeia de confiança até o Docker

```
LLM ──✗──► Docker                       o LLM nunca acessa o Docker
LLM ──► Backend (Policy → Executor)     o backend decide
Backend ──► docker-socket-proxy         o backend nunca recebe o socket (poder de root)
Proxy ──► Docker Engine                 só os endpoints liberados passam
```

- **LLM**: nunca acessa o Docker.
- **Backend**: nunca recebe poder irrestrito, porque não monta o socket.
- **Proxy**: limita a superfície disponível a listar, inspecionar, ler logs e reiniciar.
- **Docker**: só recebe operações permitidas.

**Limitação honesta (confirmada na fatia 3):** o proxy restringe **quais tipos de operação** passam (por
exemplo, "restart sim, exec e create não"), mas **não** restringe **quais containers**: as regras dele são
por padrão de caminho, sem filtro por nome ou label. A allowlist por container é aplicada pelo backend e é
uma garantia **contra o agente**, não uma barreira contra um backend comprometido. Se o backend fosse
comprometido, o atacante poderia ler o `inspect` e os logs de qualquer container (e, a partir da fatia 8,
reiniciá-los), mas não criar containers privilegiados nem executar comandos dentro deles. Detalhes e riscos
residuais na [ADR-011](adr/0011-linuxserver-socket-proxy.md) e no documento 06 (TM-B6-04, TM-B6-08).

## 3. Diagrama de alto nível

```
                         ┌────────────────────────────────────────────────────────────────────────┐
  Usuário                │                    devops-agent (Spring Boot, 1 deployable)            │
  (curl / Swagger /      │                                                                        │
   dashboard V6)         │  ┌──────────────────────────────────────────────────────────────────┐  │
        │  HTTPS + JWT   │  │ API Layer (REST + OpenAPI)   ◄──  Security Filter Chain (JWT)    │  │
        └───────────────►│  └───────┬──────────────────────────────┬──────────────────┬────────┘  │
                         │          │ comandos                     │ consultas        │ decisão   │
                         │          ▼                              ▼                  ▼           │
                         │  ┌────────────────┐   ┌──────────────────────┐   ┌──────────────────┐   │
                         │  │ Conversation & │   │ Execution / Audit    │   │ Approval Service │   │
                         │  │ Execution Svc  │   │ Query Services       │   │ (máquina de      │   │
                         │  └───────┬────────┘   └──────────────────────┘   │  estados)        │   │
                         │          │ dispatch (async, via ExecutionDispatcher)└──────┬─────────┘  │
                         │          ▼                                                 │ retoma     │
                         │  ┌──────────────────────────────────────────────────────────▼───────┐  │
                         │  │                    AGENT ORCHESTRATOR                            │  │
                         │  │  loop limitado · orçamentos · estados · decide o próximo passo   │  │
                         │  └───┬───────────────────┬──────────────────────────┬───────────────┘  │
                         │      │                   │                          │                  │
                         │      ▼                   ▼                          ▼                  │
                         │ ┌──────────┐   ┌──────────────────┐     ┌──────────────────────┐       │
                         │ │ Context  │   │   LLM Gateway    │     │    Tool Registry     │       │
                         │ │ Builder  │   │ (port + adapter, │     │ (catálogo tipado,    │       │
                         │ │ (determ.)│   │ tokens, custo)   │     │  schemas, risco)     │       │
                         │ └────┬─────┘   └────────┬─────────┘     └──────────┬───────────┘       │
                         │      │                  │                          ▼                   │
                         │      │                  │               ┌──────────────────────┐       │
                         │      │                  │               │ Policy / Permission  │       │
                         │      │                  │               │ Engine (determ.)     │──► ALLOW / REQUIRE_APPROVAL / DENY
                         │      │                  │               └──────────┬───────────┘       │
                         │      │                  │                          ▼                   │
                         │      │                  │               ┌──────────────────────┐       │
                         │      │                  │               │   Tool Executor      │       │
                         │      │                  │               │ timeout · retry ·    │       │
                         │      │                  │               │ truncagem · redaction│       │
                         │      │                  │               │ · registro · métricas│       │
                         │      │                  │               └──────────┬───────────┘       │
                         │      │                  │                          ▼                   │
                         │      │                  │    ┌─────────────────────────────────────┐   │
                         │      │                  │    │ Adapters: Docker │ Git* │ GitHub*   │   │
                         │      │                  │    │           CI/CD* │ Prometheus*      │   │
                         │      │                  │    └────────┬────────────────────────────┘   │
                         │      ▼                  │             │                                │
                         │  ┌──────────────────────┴─────────────┴──────┐  ┌───────────────────┐  │
                         │  │ Persistence (JPA + Flyway)  │  Audit Log  │  │ Actuator/Micrometer│ │
                         │  └──────────────┬──────────────────────────────┘ └────────┬──────────┘ │
                         └─────────────────┼─────────────────────────────────────────┼────────────┘
                                           │                                         │ /actuator/prometheus
                     ┌─────────────────────▼──┐   ┌────────────────────┐   ┌─────────▼──────┐   ┌─────────┐
                     │      PostgreSQL        │   │ Provedor de LLM    │   │  Prometheus    ├──►│ Grafana │
                     │ (fonte da verdade)     │   │ (API externa, com  │   └────────────────┘   └─────────┘
                     └────────────────────────┘   │  tool calling)     │
                                                  └────────────────────┘
                     ┌────────────────────────┐   ┌────────────────────┐
                     │ docker-socket-proxy    ├──►│ Docker Engine      │──► containers alvo (ex.: demo-api)
                     │ (allowlist de endpoints)   │ (host)             │
                     └────────────────────────┘   └────────────────────┘

   * = fases posteriores.   Redis e RabbitMQ entram quando houver gatilho objetivo (seção 8).
```

## 4. Componentes: responsabilidade, fase e comunicação

| # | Componente | Responsabilidade | Fase | Comunica-se com |
|---|---|---|---|---|
| 1 | **API Layer** ("gateway") | Expor a API REST versionada (`/api/v1`), validar a entrada (Bean Validation), traduzir erros para `application/problem+json` (RFC 9457) e publicar o OpenAPI. **Não há API Gateway separado** (Spring Cloud Gateway, Kong etc.): com um único serviço, ele seria só mais um salto de rede. | MVP | Chama os serviços de aplicação dos módulos, in-process. |
| 2 | **Authentication** | Spring Security. No MVP o próprio backend emite o JWT (login com usuário e senha, BCrypt) e valida o token como *OAuth2 Resource Server*. Na V5 entram API keys e, opcionalmente, um IdP externo OIDC. | MVP | Filtro HTTP que popula o `SecurityContext` usado por todos os módulos. |
| 3 | **Agent Orchestrator** | Coração do sistema. Executa o loop do agente como uma **máquina de estados persistida**, aplica os orçamentos (passos, tempo, tokens), chama o LLM, envia as propostas à cadeia de validação (seção 5.2), pausa para aprovação e produz a resposta final. **É ele quem controla o loop, não o LLM nem o framework.** Veja a [ADR-002](adr/0002-backend-controla-o-loop.md). | MVP | LLM Gateway, Context Builder, Tool Registry/Policy/Executor, Approval Service, repositórios. |
| 4 | **LLM Gateway** | Interface `LlmGateway` (*port*), com um adaptador por provedor (o primeiro é OpenAI, [ADR-010](adr/0010-llm-gateway-agnostico-de-provedor.md)). O orchestrator não sabe qual provedor está em uso. Traduz o catálogo de ferramentas para o formato de tool calling do provedor, aplica timeout e retentativa (429/5xx), conta tokens, estima custo e emite métricas. Nos testes é substituído por um **LLM falso roteirizado**, inclusive um "LLM malicioso" (H1 do documento 01). | MVP | Provedor externo via HTTPS. |
| 5 | **Tool Registry** | Catálogo **fechado** das ferramentas. Cada ferramenta é uma classe com uma definição declarativa: nome, descrição, schema dos parâmetros, nível de risco, permissão exigida, se exige aprovação, timeout e se é idempotente. O registry fornece ao LLM **só as ferramentas permitidas** para aquele usuário, ambiente e nível de autonomia. | MVP | Orchestrator (consulta), Policy Engine. |
| 6 | **Tool Execution Layer** | Executa uma chamada já autorizada: aplica timeout, faz retentativa só quando a ferramenta é idempotente, trunca e mascara a saída, grava o `ToolExecution` **antes** (`RUNNING`) e **depois** (`SUCCEEDED`/`FAILED`/…) da chamada externa e emite métricas. | MVP | Adapters de sistemas externos, Audit. |
| 7 | **Policy / Permission Engine** | Decisão **determinística** (sem LLM): `ALLOW`, `REQUIRE_APPROVAL` ou `DENY` com motivo, a partir das permissões do usuário, do risco da ferramenta, do nível de autonomia do ambiente, da allowlist e do orçamento restante. É **fail-closed**: qualquer erro inesperado na avaliação vira `DENY`. | MVP | Chamado pelo Orchestrator antes de cada execução. |
| 7b | **Approval Service** | Cria, expira, aprova e rejeita solicitações. Ao aprovar, **retoma** a execução com a chamada exata que foi aprovada. | MVP | API Layer, Orchestrator, Audit. |
| 8 | **PostgreSQL** | Fonte da verdade: usuários, ambientes, conversas, mensagens, execuções, chamadas de ferramenta, aprovações, auditoria, incidentes e deploys. | MVP | JPA/Hibernate + Flyway. |
| 9 | **Redis** | **Adiado.** Entra quando houver cache compartilhado, rate limiting distribuído ou locks entre várias instâncias. | V4/V5 | — |
| 10 | **Message Broker (RabbitMQ)** | **Adiado para a V2.** Entra com operações longas (deploy, pipelines), webhooks do GitHub e notificações. No MVP, o despacho assíncrono fica atrás de uma interface `ExecutionDispatcher`. | V2 | — |
| 11 | **Observability** | Logs JSON correlacionados, métricas Micrometer expostas em `/actuator/prometheus`, Prometheus coletando e Grafana com dashboard provisionado. Tracing (OpenTelemetry) na V3. | MVP (logs + métricas) | Prometheus faz *pull* do backend. |
| 12 | **External Integrations** | Adapters para os sistemas operados. MVP: Docker Engine API via proxy. V2: Git, GitHub API, GitHub Actions. V3: Prometheus como **fonte de dados** das aplicações monitoradas. | MVP → V3 | HTTP (REST). |
| 13 | **Frontend / Dashboard** | **Não entra no MVP.** Swagger UI e arquivos `.http` bastam para a demo. O dashboard vem na V6, consumindo a mesma API pública e renderizando o histórico de `ToolExecution` tal como ele é. | V6 | API REST. |
| + | **Context Builder** | Monta, de forma **determinística**, o contexto enviado ao LLM: ambiente, serviços permitidos, últimas execuções e ações, e (depois) incidentes e deploys recentes. Não usa o LLM para decidir o que buscar. | MVP (básico) | Repositórios. |
| + | **Audit** | Registro *append-only* de toda decisão e ação, consultável por recurso, usuário e execução. | MVP | Chamado pelo Executor, pelo Approval Service e pelo Policy Engine (negações). |
| + | **docker-socket-proxy** | Container que expõe **somente** os endpoints da Docker API usados (list, inspect, logs, restart). O backend nunca vê o socket. Veja a [ADR-003](adr/0003-sem-shell-docker-via-proxy.md). | MVP | Backend → proxy (HTTP na rede interna do compose) → socket. |
| + | **demo-api** | Aplicação-alvo pequena, com endpoints para simular falhas (travar, sair com erro, ficar `unhealthy`). Sem ela não há o que demonstrar. | MVP | É observada e operada pelo agente. |

### Por que o `docker compose` do MVP difere da lista inicial

| Serviço | No MVP? | Justificativa |
|---|---|---|
| backend | Sim | O sistema em si. |
| PostgreSQL | Sim | Fonte da verdade. |
| Prometheus + Grafana | Sim | Custo baixo e alto valor: tornam o agente **observável** desde o dia 1 (requisito explícito). |
| docker-socket-proxy | Sim (**adicionado**) | É um requisito de segurança, não um enfeite. Sem ele, o backend teria acesso de root ao host. |
| demo-api | Sim (**adicionado**) | É o alvo da demo. Sem ele, o agente não tem nada realista para diagnosticar. |
| Redis | **Não** | Ainda não existe um problema que ele resolva (seção 8). |
| RabbitMQ | **Não** | Idem. Entra na V2 com um caso de uso real. |

## 5. Fluxo do agente

### 5.1 Quem decide cada etapa

O fluxo pedido (Intent → Context → Planning → Tool Selection → Permission → Execution → Analysis → Next
Action → Final Response) é implementado assim. O ponto central é **separar o que é determinístico do que
é delegado ao LLM**.

| Etapa | Quem faz | Como |
|---|---|---|
| Intent Understanding | LLM (implícito) | No MVP **não** há uma chamada separada de "classificação de intenção", porque dobraria custo e latência sem ganho claro. O LLM expressa a intenção escolhendo ferramentas. Uma etapa explícita só entra se as métricas mostrarem necessidade. |
| Context Retrieval | **Código** | O Context Builder busca ambiente, serviços permitidos, histórico recente e resumo da conversa no PostgreSQL. |
| Planning | LLM, limitado | O LLM decide o próximo passo, dentro do orçamento. Planos explícitos (lista de passos antes de agir) ficam para a V4. |
| Tool Selection | LLM **propõe** | O LLM só vê as ferramentas já filtradas para aquele usuário, ambiente e nível de autonomia. |
| Permission Check | **Código** | A cadeia de validação (5.2). O LLM não tem como pular essa etapa. |
| Tool Execution | **Código** | Executor + adapter. |
| Result Analysis | **Código + LLM** | Regras determinísticas geram **achados** (`exited(137)` → provável OOM, por exemplo). O LLM interpreta logs e explica. |
| Next Action | LLM, limitado | O loop continua enquanto houver orçamento e o LLM pedir ferramentas. |
| Final Response | **LLM + código** | O texto vem do LLM. A lista de **ações** (propostas e desfechos) vem dos registros de `ToolExecution` (seção 6). |

### 5.2 Cadeia de validação de cada proposta

Toda proposta do LLM, por exemplo `{"tool": "restartContainer", "arguments": {"service": "demo-api"}}`,
passa por esta cadeia **em ordem**. Qualquer "não" encerra a cadeia, e a proposta vira um
`ToolExecution` com status `DENIED` e o motivo:

```
 1. A ferramenta existe no catálogo?                               não → DENIED (UNKNOWN_TOOL)
 2. Ela está habilitada neste ambiente e nível de autonomia?      não → DENIED (NOT_ALLOWED_BY_AUTONOMY)
 3. Os argumentos são válidos pelo schema?                         não → DENIED (INVALID_ARGUMENTS)
 4. O recurso-alvo está na allowlist do ambiente?                  não → DENIED (RESOURCE_NOT_ALLOWED)
 5. O usuário solicitante tem a permissão exigida?                 não → DENIED (INSUFFICIENT_PERMISSION)
 6. Ainda há orçamento na execução?                                não → DENIED (BUDGET_EXCEEDED)
 7. A ferramenta exige aprovação (risco × autonomia, seção 5.3)?   sim → WAITING_APPROVAL (pausa)
 8. Executa: Executor → adapter → proxy → Docker
```

**Por que nessa ordem:** as verificações estruturais e baratas (existência, schema) vêm antes das
semânticas (allowlist, permissão). Não dá para checar a allowlist sem antes ter um argumento válido.
Toda negação é **auditada**, o que é útil também para detectar tentativas de prompt injection (ver RF-49
e o H1 do documento 01).

### 5.3 Níveis de autonomia (risco × autonomia)

O nível de autonomia é **configuração de segurança do ambiente, não decisão do LLM** (ver a
[ADR-008](adr/0008-niveis-de-autonomia.md) e RF-70 a RF-73). A decisão do Policy Engine para uma chamada
permitida nas etapas 1 a 6:

| Risco da ferramenta ↓ / Autonomia → | `OBSERVE_ONLY` | `ASSISTED` | `AUTOMATED` (pós-MVP) |
|---|---|---|---|
| `READ_ONLY` | ALLOW | ALLOW | ALLOW |
| `LOW_RISK` | DENY | ALLOW (auditado) | ALLOW |
| `HIGH_RISK` | DENY | **REQUIRE_APPROVAL** | ALLOW **só** se uma regra de pré-autorização casar; senão REQUIRE_APPROVAL |
| `DESTRUCTIVE` | DENY | **REQUIRE_APPROVAL** | **REQUIRE_APPROVAL** (nunca automático) |

Mais duas regras:

- Uma ferramenta que declara `requiresApproval = true` exige aprovação em qualquer nível que a permita.
- **Nomenclatura:** usei `OBSERVE_ONLY` em vez de `READ_ONLY` para o nível de autonomia, porque
  `READ_ONLY` já é um nível de **risco** de ferramenta. Ter o mesmo nome para dois conceitos diferentes
  confundiria código, logs e conversas.

**Observação sobre o `AUTOMATED`:** o exemplo "healthcheck falhou → restart → healthcheck" envolve na
verdade **duas** mudanças independentes: (a) executar uma ação de risco sem aprovação por ação (RF-72) e
(b) o agente ser **disparado por um evento**, sem um humano iniciando (RF-73). A (b) exige um "ator de
sistema" na auditoria e um mecanismo de gatilho. As duas ficam para depois do MVP.

## 6. Modelo de execução: `AgentExecution`, `ToolExecution` e `Approval`

### 6.1 Estrutura

Uma execução do agente (**agregado raiz**) contém N chamadas de ferramenta. Cada chamada pode ter no
máximo uma aprovação.

```
AgentExecution #123   requested_by=U1 · environment=local · autonomy=ASSISTED
│                     RUNNING → WAITING_APPROVAL → RUNNING → COMPLETED
│
├── ToolExecution #1   getContainerStatus(demo-api)   READ_ONLY   SUCCEEDED
├── ToolExecution #2   getContainerLogs(demo-api)     READ_ONLY   SUCCEEDED
├── ToolExecution #3   restartContainer(demo-api)     HIGH_RISK   WAITING_APPROVAL → RUNNING → SUCCEEDED
│     └── Approval #A1   PENDING → APPROVED  (por U1, 14:03, params_hash=…)
└── ToolExecution #4   getContainerStatus(demo-api)   READ_ONLY   SUCCEEDED   (verificação pós-ação)
```

**Por que a `Approval` fica pendurada na `ToolExecution`, e não direto na execução:** uma aprovação é
sempre sobre **uma chamada específica com parâmetros exatos**. Pendurá-la na execução permitiria o
equívoco "aprovei a execução, então tudo nela está aprovado".

**Toda proposta vira uma `ToolExecution`, inclusive as negadas.** Assim, "ações propostas" e "ações
executadas" são o mesmo conjunto de registros, filtrado por status. Não existe uma lista separada de
`plannedActions` que possa divergir da realidade. (Planos explícitos antes da ação são da V4. Se
entrarem, cada passo planejado também vira um registro com status próprio.)

### 6.2 Estados

**`AgentExecution`:**

```
 QUEUED ──► RUNNING ──► COMPLETED
              │  ▲
              │  └──────────── (aprovado: retoma com a chamada exata)
              ▼  │
       WAITING_APPROVAL ──► (rejeitado ou expirado: o LLM é informado e responde) ──► COMPLETED

 RUNNING ──► FAILED            (erro não recuperável, LLM indisponível)
 RUNNING ──► BUDGET_EXCEEDED   (tempo, iterações ou chamadas de ferramenta; RF-26)
 RUNNING ──► INTERRUPTED       (crash do backend, detectado na inicialização)
 QUEUED | RUNNING | WAITING_APPROVAL ──► CANCELLED
```

**`ToolExecution`:**

```
 PROPOSED ──► DENIED                      (motivo: UNKNOWN_TOOL, INVALID_ARGUMENTS, …)
    │
    ├──► WAITING_APPROVAL ──► REJECTED | EXPIRED | CANCELLED
    │           │
    │           └── APPROVED ──┐
    ▼                          ▼
 RUNNING ──► SUCCEEDED | FAILED | TIMED_OUT | OUTCOME_UNKNOWN
```

`OUTCOME_UNKNOWN`: o backend caiu, ou a conexão caiu, **depois** de enviar uma operação com efeito
colateral e **antes** de receber a resposta. Nesse caso o sistema não finge saber o que aconteceu. Ele
mostra o estado ao usuário, que deve verificar (com uma ferramenta read-only) antes de tentar de novo.

### 6.3 Resposta da execução (projeção dos registros)

A aprovação e o resultado da execução são **dimensões independentes**, por isso aparecem em campos
separados, em vez de um status combinado como `APPROVED_AND_EXECUTED`. Combinar as duas coisas geraria
uma explosão de valores (`APPROVED_AND_FAILED`, `APPROVED_AND_TIMED_OUT`, …).

```json
{
  "executionId": "0b7c…",
  "status": "COMPLETED",
  "answer": "O demo-api estava unhealthy: os logs mostram o pool de conexões esgotado. Reiniciei o container após sua aprovação e ele voltou a responder.",
  "actions": [
    { "seq": 1, "tool": "getContainerStatus", "target": "demo-api", "risk": "READ_ONLY", "status": "SUCCEEDED", "durationMs": 38 },
    { "seq": 2, "tool": "getContainerLogs",   "target": "demo-api", "risk": "READ_ONLY", "status": "SUCCEEDED", "durationMs": 112 },
    { "seq": 3, "tool": "restartContainer",   "target": "demo-api", "risk": "HIGH_RISK", "status": "SUCCEEDED", "durationMs": 4210,
      "approval": { "id": "a1…", "decision": "APPROVED", "decidedBy": "operador@local", "decidedAt": "2026-09-26T14:03:11Z" } },
    { "seq": 4, "tool": "getContainerStatus", "target": "demo-api", "risk": "READ_ONLY", "status": "SUCCEEDED", "durationMs": 41 }
  ]
}
```

Se o usuário tivesse rejeitado o restart, o item 3 apareceria com `"status": "REJECTED"`, e o item 4
provavelmente não existiria. O frontend (V6) só renderiza o que está nos registros.

## 7. Exemplo de fluxo ponta a ponta: "Se o container estiver travado, reinicie"

```
1. POST /api/v1/conversations/{id}/messages  {"content": "Se o demo-api estiver travado, reinicie"}
   Idempotency-Key: 7f3e…
   → 202 Accepted {executionId: E1, status: QUEUED}

2. Orchestrator(E1): RUNNING
   Context Builder → ambiente "local" (DEV, autonomia ASSISTED), serviços permitidos [demo-api, ...]
   LLM → propõe getContainerStatus(service="demo-api")
   Cadeia de validação → READ_ONLY → ALLOW
   Executor → Docker API (via proxy) → {state: running, health: unhealthy, restarts: 0}
   Diagnóstico determinístico → achado: UNHEALTHY
   LLM → propõe getContainerLogs(service="demo-api", tail=200)
   Validação → ALLOW → Executor → logs (truncados + mascarados)
   LLM → "o processo está travado (pool esgotado)" → propõe restartContainer(service="demo-api")
   Validação → HIGH_RISK em ASSISTED → REQUIRE_APPROVAL
   Approval Service → cria A1 {tool, params, hash, risco, impacto, justificativa, expira em 15 min}
   ToolExecution #3 e E1 → WAITING_APPROVAL      (a thread é liberada; nada fica bloqueado)

3. GET /api/v1/executions/E1 → mostra o pedido de aprovação A1, com o texto explicativo

4. POST /api/v1/approvals/A1/decision {"decision": "APPROVE", "comment": "ok"}
   → verifica permissão APPROVE, expiração, uso único e hash dos parâmetros
   → transição condicional PENDING → APPROVED (seção 9: um clique duplo não reinicia duas vezes)
   → Audit: APPROVAL_GRANTED por U1
   → E1 volta para RUNNING e executa restartContainer(demo-api) EXATAMENTE como aprovado
   → ToolExecution #3: RUNNING (gravado ANTES da chamada) → Docker restart → SUCCEEDED
   → LLM → propõe getContainerStatus (verificação) → healthy
   → LLM → resposta final
   E1 → COMPLETED

5. GET /api/v1/audit-events?resource=container:demo-api
   → "U1 pediu às 14:02; U1 aprovou às 14:03; o agente pediu o restart porque o container estava
      unhealthy e os logs mostravam X; resultado: sucesso em 4,2 s"
```

## 8. Síncrono x assíncrono e quando entram Redis e RabbitMQ

### No MVP

- **Síncrono**: CRUD de ambientes e serviços, consultas (execuções, auditoria, ferramentas), login e
  a *decisão* de aprovação (a resposta é imediata, e a retomada da execução é assíncrona).
- **Assíncrono**: a execução do agente. Ela leva de segundos a minutos (é dominada pela latência do
  LLM) e pode ficar pausada aguardando aprovação. Manter uma requisição HTTP aberta durante esse tempo
  seria frágil.
- **Como, sem broker**: um executor in-process com concorrência limitada. O estado fica **inteiro no
  PostgreSQL**, então a durabilidade vem do banco, não da fila. O cliente acompanha por polling
  (`GET /executions/{id}`) e, na V1, também por SSE.
- **O que se perde sem broker, conscientemente**: se o backend cair no meio de um passo `RUNNING`, a
  execução não é retomada automaticamente. Ela é marcada `INTERRUPTED` na inicialização. Para um MVP
  de uso pessoal isso é aceitável e documentado. Execuções em `WAITING_APPROVAL` **não** são perdidas.

### Gatilhos objetivos para introduzir cada tecnologia

| Tecnologia | Entra quando… | Fase provável |
|---|---|---|
| **RabbitMQ** | Surgem operações longas com acompanhamento (deploy, pipeline com polling), eventos externos (webhooks do GitHub), notificações com retry e DLQ, ou a necessidade de separar *workers* da API. Nesse ponto, redelivery e dead-letter passam a resolver problemas reais. | V2 |
| **Redis** | Mais de uma instância do backend (locks distribuídos por recurso, fan-out de SSE entre instâncias), rate limiting por tenant ou API key, ou um cache de contexto com necessidade comprovada por métrica. | V4/V5 |

A troca "in-process → RabbitMQ" é local: implementa-se um novo `ExecutionDispatcher`. Esse é um bom
exemplo, para entrevista, de **projetar para a mudança sem antecipá-la**.

## 9. Idempotência (RF-27)

Um restart **não** é idempotente no efeito: dois restarts causam duas interrupções. Por isso cada ponto
onde uma duplicação pode acontecer tem um mecanismo próprio:

| Onde pode duplicar | Causa | Mecanismo | Fase |
|---|---|---|---|
| Criar execução (`POST …/messages`) | O cliente repete a requisição após um timeout | Header `Idempotency-Key`, com unicidade (usuário + chave) no banco. A repetição devolve o **mesmo** `executionId`. A mesma chave com um corpo diferente devolve `422`. | MVP |
| Decidir aprovação | Clique duplo, dois aprovadores ao mesmo tempo | Transição condicional `PENDING → APPROVED/REJECTED` (update com `WHERE status = 'PENDING'` ou lock otimista com `@Version`). Repetir a **mesma** decisão devolve o estado atual. Uma decisão **conflitante** devolve `409`. | MVP |
| Retomar a execução após a aprovação | Duas threads ou instâncias tentam retomar | Transição condicional `ToolExecution: WAITING_APPROVAL → RUNNING`. Só quem vence a transição chama o Docker. | MVP |
| Retentativa de ferramenta | Erro transitório | Retentativa automática **só** para ferramentas read-only e idempotentes. Ferramentas com efeito colateral nunca são repetidas automaticamente, e um caso duvidoso vira `OUTCOME_UNKNOWN` (seção 6.2). | MVP |
| Consumo de mensagens | O RabbitMQ entrega *at-least-once*, então duplicatas são esperadas | Tabela de mensagens processadas (unicidade por `messageId`) + as mesmas transições condicionais. Publicação via **transactional outbox**, para não perder mensagens entre o commit e o envio. | V2 |

## 10. Memória e contexto (visão geral; o detalhe vem na V4)

| Tipo | O que é | Onde fica | Fase |
|---|---|---|---|
| Memória de conversa | Mensagens do usuário e do agente, e chamadas de ferramenta da conversa | PostgreSQL, com uma janela das últimas N mensagens enviada ao LLM | MVP |
| Contexto operacional | Ambiente atual, serviços, últimas ações e execuções, deploys e incidentes recentes | Consultado no PostgreSQL pelo Context Builder a cada execução | MVP (básico) → V4 |
| Dados persistentes | Tudo o que é fato do sistema (auditoria, execuções, aprovações) | PostgreSQL | MVP |
| Conhecimento e documentação | Runbooks, README dos serviços, postmortems | Começa como texto no banco com busca full-text do PostgreSQL. Vetores (pgvector) **só** se a busca textual se mostrar insuficiente. Veja a [ADR-007](adr/0007-sem-memoria-vetorial-no-mvp.md). | V4 |

Regra geral: **PostgreSQL é a fonte da verdade. Redis, quando entrar, guarda apenas dados efêmeros ou
derivados**, que podem ser perdidos sem prejuízo.

## 11. Multi-tenancy desde já (sem implementar SaaS agora)

A plataforma multi-tenant só vem na V5, mas **todas as tabelas de domínio terão `organization_id` desde
o MVP**, com uma única organização criada no seed. Custa quase nada agora e evita uma migração dolorosa
depois. Estratégia prevista: schema compartilhado com `organization_id`, filtro obrigatório na camada de
acesso a dados e **Row-Level Security do PostgreSQL** como defesa em profundidade. Veja a
[ADR-005](adr/0005-organization-id-desde-o-mvp.md).

## 12. Organização do código (módulos)

```
com.devopsaaas
 ├── identity       usuários, autenticação, papéis (organizações na V5)
 ├── environment    ambientes, serviços permitidos (allowlist), nível de autonomia
 ├── conversation   conversas e mensagens
 ├── agent          orchestrator, context builder, AgentExecution, orçamentos
 ├── llm            port LlmGateway + adapters + contabilização de tokens e custo
 ├── tool           registry, policy engine, executor, ToolExecution, aprovações
 │    └── container ferramentas de container + port ContainerRuntime (sem nenhuma dependência de Docker)
 ├── integration
 │    └── docker    adapter ContainerRuntime → Docker Engine API (via proxy)
 ├── audit          registro e consulta de auditoria
 └── shared         erros, contexto de tenant, observabilidade, utilitários
```

As regras de dependência (por exemplo, "`tool` não depende de `agent`" e "`llm` não conhece JPA") serão
verificadas por testes de arquitetura, para que as fronteiras não se degradem com o tempo. Entre elas: "`tool`
não depende de `integration`" (as ferramentas conhecem só o port; veja o documento 05).

## 13. Stack e justificativa por fase

| Tecnologia | Uso | Fase | Observação |
|---|---|---|---|
| Java (LTS) | Linguagem | MVP | Proposta: a versão LTS mais recente suportada por todo o ecossistema no momento do setup (21 ou 25). **Vou confirmar a compatibilidade antes de criar o projeto.** |
| Spring Boot | Framework | MVP | A versão estável mais recente no momento do setup. **A confirmar na documentação oficial**, porque a linha de versões pode ter mudado. |
| Spring Security (Resource Server + JWT) | AuthN/AuthZ | MVP | |
| Spring Data JPA + Flyway | Persistência e migrações | MVP | |
| PostgreSQL | Banco | MVP | |
| springdoc-openapi | OpenAPI/Swagger | MVP | |
| Micrometer + Actuator | Métricas | MVP | |
| Prometheus + Grafana | Observabilidade | MVP | |
| JUnit 5, Mockito, AssertJ | Testes unitários | MVP | |
| Testcontainers | Testes de integração com PostgreSQL real | MVP | |
| WireMock | Simular Docker API, provedor de LLM e GitHub | MVP | |
| ArchUnit | Testes de fronteira entre módulos | MVP | |
| GitHub Actions | CI (build, testes, qualidade, imagem) | MVP | O CD vem na V7. |
| Cliente da Docker Engine API | Integração Docker | MVP | Proposta: chamar a **API REST do Docker Engine** diretamente com o `RestClient` do Spring, através do proxy. É uma superfície pequena, fácil de testar com um stub HTTP e sem dependência pesada. Alternativa: a biblioteca `docker-java`. *Fatia 3: adotado; o stub usa o `HttpServer` do próprio JDK.* |
| Provedor de LLM com tool calling | IA | MVP | OpenAI como primeira implementação, atrás do `LlmGateway`. O modelo fica em configuração ([ADR-010](adr/0010-llm-gateway-agnostico-de-provedor.md)). |
| RabbitMQ | Mensageria | V2 | Seção 8. |
| Redis | Cache, locks, rate limit | V4/V5 | Seção 8. |
| OpenTelemetry | Tracing | V3 | |
| OAuth2/OIDC externo, API keys | SaaS | V5 | |

## 14. Sequência de documentos e primeiras fatias de implementação

```
01 — Visão e problema                     ✔
02 — Requisitos                           ✔
03 — Arquitetura                          ✔  (este documento)
04 — Modelo de dados                      ✔
05 — Contratos das ferramentas            ✔
06 — Threat model                         ✔
07 — Plano do MVP                         ✔
     ↓
Implementação incremental
```

As **ADRs** não são um documento numerado: elas ficam em `docs/adr/` e são escritas **no momento em que
cada decisão é tomada**, durante todas as fases.

Ordem prevista das fatias de implementação (detalhada no documento 07). Cada fatia é testada e validada
antes da seguinte:

```
0. esqueleto: projeto, compose, CI, Flyway, autenticação, cadastro de ambiente e allowlist
1. listContainers            (primeira ferramenta ponta a ponta: registry → policy → executor → audit)
2. getContainerStatus        (inspect)
3. getContainerLogs          (truncagem + mascaramento)
4. diagnóstico determinístico
5. loop do agente com LLM    (primeiro com LLM falso, depois com o real)
6. aprovação                 (máquina de estados, expiração, idempotência)
7. restartContainer          (+ verificação pós-ação)
```

## 15. Decisões fechadas (2026-09-26)

| Decisão | Escolha |
|---|---|
| Arquitetura | Monólito modular ([ADR-001](adr/0001-monolito-modular.md)) |
| Persistência | PostgreSQL no MVP |
| Redis | Fase posterior, quando houver necessidade concreta de cache, locks, rate limiting ou contexto temporário ([ADR-004](adr/0004-adiar-redis-e-rabbitmq.md)) |
| RabbitMQ | Fase posterior, quando o processamento precisar ser desacoplado em workers ([ADR-004](adr/0004-adiar-redis-e-rabbitmq.md)) |
| Execução assíncrona no MVP | `ExecutionDispatcher` in-process (background) + polling em `GET /executions/{id}` |
| LLM | OpenAI inicialmente, atrás do `LlmGateway`, com um único modelo definido em configuração ([ADR-010](adr/0010-llm-gateway-agnostico-de-provedor.md)) |
| Docker local | Windows + WSL2 + Docker Desktop |
| Docker em CI e produção | Linux + Docker |
| Acesso ao Docker | Nunca diretamente pelo backend; sempre via docker-socket-proxy ([ADR-003](adr/0003-sem-shell-docker-via-proxy.md)) |
| Independência de plataforma | O backend depende **apenas do contrato HTTP do proxy** (URL em configuração). Nenhum caminho de Windows, WSL2 ou Docker Desktop entra no código. Só o `docker-compose` monta o socket, e **apenas no container do proxy**. |
| Idioma | Documentação, ADRs, requisitos e issues em português. Código, API, banco, enums, logs técnicos, testes e commits em inglês ([CONTRIBUTING](../CONTRIBUTING.md)) |

**Pendente de verificação na implementação** (depende de documentação atual):

- versões de Java e Spring Boot;
- qual API da OpenAI usar e se o adapter usa um SDK, o Spring AI (com a execução automática de
  ferramentas desligada, conforme a ADR-002) ou um cliente HTTP próprio;
- a configuração do proxy e o caminho do socket dentro do Docker Desktop com WSL2.
