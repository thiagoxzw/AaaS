# 03 — Arquitetura de alto nível

> Status: **proposta**. Esta é a visão de alto nível. O detalhamento de cada parte (modelo de dados,
> contratos da API, orquestrador, ferramentas, segurança) vem nos próximos documentos.

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

## 2. Diagrama de alto nível

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

   * = fases posteriores.   Redis e RabbitMQ entram quando houver gatilho objetivo (seção 6).
```

## 3. Componentes: responsabilidade, fase e comunicação

| # | Componente | Responsabilidade | Fase | Comunica-se com |
|---|---|---|---|---|
| 1 | **API Layer** ("gateway") | Expor a API REST versionada (`/api/v1`), validar a entrada (Bean Validation), traduzir erros para `application/problem+json` (RFC 9457) e publicar o OpenAPI. **Não há API Gateway separado** (Spring Cloud Gateway, Kong etc.): com um único serviço, ele seria só mais um salto de rede. | MVP | Chama os serviços de aplicação dos módulos, in-process. |
| 2 | **Authentication** | Spring Security. No MVP o próprio backend emite o JWT (login com usuário e senha, BCrypt) e valida o token como *OAuth2 Resource Server*. Na V5 entram API keys e, opcionalmente, um IdP externo OIDC. | MVP | Filtro HTTP que popula o `SecurityContext` usado por todos os módulos. |
| 3 | **Agent Orchestrator** | Coração do sistema. Executa o loop do agente como uma **máquina de estados persistida**, aplica os orçamentos (passos, tempo, tokens), chama o LLM, envia as propostas de chamada à cadeia Registry → Policy → Executor, pausa para aprovação e produz a resposta final. **É ele quem controla o loop, não o LLM nem o framework.** Veja a [ADR-002](adr/0002-backend-controla-o-loop.md). | MVP | LLM Gateway, Context Builder, Tool Registry/Policy/Executor, Approval Service, repositórios. |
| 4 | **LLM Gateway** | Interface (`port`) com um adaptador por provedor. Traduz o catálogo de ferramentas para o formato de tool calling do provedor, aplica timeout e retentativa (429/5xx), conta tokens, estima custo e emite métricas. Nos testes é substituído por um **LLM falso roteirizado**. | MVP | Provedor externo via HTTPS. |
| 5 | **Tool Registry** | Catálogo **fechado** das ferramentas. Cada ferramenta é uma classe com uma definição declarativa: nome, descrição, schema dos parâmetros, nível de risco, permissão exigida, se exige aprovação, timeout e se é idempotente. O registry fornece ao LLM **só as ferramentas permitidas** para aquele usuário e ambiente. | MVP | Orchestrator (consulta), Policy Engine. |
| 6 | **Tool Execution Layer** | Executa uma chamada já autorizada: aplica timeout, faz retentativa só quando a ferramenta é idempotente, trunca e mascara a saída, grava o `ToolExecution` **antes** (`STARTED`) e **depois** (`SUCCEEDED`/`FAILED`) da chamada externa e emite métricas. | MVP | Adapters de sistemas externos, Audit. |
| 7 | **Policy / Permission Engine** | Decisão **determinística** (sem LLM): `ALLOW`, `REQUIRE_APPROVAL` ou `DENY`, a partir do papel do usuário, do risco da ferramenta, da classificação do ambiente (DEV/PROD), da allowlist de recursos e do orçamento restante. | MVP | Chamado pelo Orchestrator antes de cada execução. |
| 7b | **Approval Service** | Cria, expira, aprova e rejeita solicitações. Ao aprovar, **retoma** a execução com a chamada exata que foi aprovada. | MVP | API Layer, Orchestrator, Audit. |
| 8 | **PostgreSQL** | Fonte da verdade: usuários, ambientes, conversas, mensagens, execuções, chamadas de ferramenta, aprovações, auditoria, incidentes e deploys. | MVP | JPA/Hibernate + Flyway. |
| 9 | **Redis** | **Adiado.** Entra quando houver cache compartilhado, rate limiting distribuído ou locks entre várias instâncias. | V4/V5 | — |
| 10 | **Message Broker (RabbitMQ)** | **Adiado para a V2.** Entra com operações longas (deploy, pipelines), webhooks do GitHub e notificações. No MVP, o despacho assíncrono fica atrás de uma interface `ExecutionDispatcher`. | V2 | — |
| 11 | **Observability** | Logs JSON correlacionados, métricas Micrometer expostas em `/actuator/prometheus`, Prometheus coletando e Grafana com dashboard provisionado. Tracing (OpenTelemetry) na V3. | MVP (logs + métricas) | Prometheus faz *pull* do backend. |
| 12 | **External Integrations** | Adapters para os sistemas operados. MVP: Docker Engine API via proxy. V2: Git, GitHub API, GitHub Actions. V3: Prometheus como **fonte de dados** das aplicações monitoradas. | MVP → V3 | HTTP (REST). |
| 13 | **Frontend / Dashboard** | **Não entra no MVP.** Swagger UI e arquivos `.http` bastam para a demo. O dashboard vem na V6, consumindo a mesma API pública. | V6 | API REST. |
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
| Redis | **Não** | Ainda não existe um problema que ele resolva (seção 6). |
| RabbitMQ | **Não** | Idem. Entra na V2 com um caso de uso real. |

## 4. Fluxo do agente: quem decide cada etapa

O fluxo pedido (Intent → Context → Planning → Tool Selection → Permission → Execution → Analysis → Next
Action → Final Response) é implementado assim. O ponto central é **separar o que é determinístico do que
é delegado ao LLM**.

| Etapa | Quem faz | Como |
|---|---|---|
| Intent Understanding | LLM (implícito) | No MVP **não** há uma chamada separada de "classificação de intenção", porque dobraria custo e latência sem ganho claro. O LLM expressa a intenção escolhendo ferramentas. Uma etapa explícita só entra se as métricas mostrarem necessidade. |
| Context Retrieval | **Código** | O Context Builder busca ambiente, serviços permitidos, histórico recente e resumo da conversa no PostgreSQL. |
| Planning | LLM, limitado | O LLM decide o próximo passo, dentro do orçamento. Planos explícitos (lista de passos antes de agir) ficam para a V4. |
| Tool Selection | LLM **propõe** | O LLM só vê as ferramentas já filtradas pelo Registry para aquele usuário e ambiente. |
| Permission Check | **Código** | O Policy Engine decide. O LLM não tem como pular essa etapa. |
| Tool Execution | **Código** | Executor + adapter. Parâmetros validados contra schema e allowlist. |
| Result Analysis | **Código + LLM** | Regras determinísticas geram **achados** (`exited(137)` → provável OOM, por exemplo). O LLM interpreta logs e explica. |
| Next Action | LLM, limitado | O loop continua enquanto houver orçamento e o LLM pedir ferramentas. |
| Final Response | **LLM + código** | O texto vem do LLM. A lista de **ações executadas** vem dos registros do banco, então o agente não consegue "dizer" que fez algo que não fez. |

### Máquina de estados da execução (visão inicial)

```
 QUEUED ──► RUNNING ──► COMPLETED
              │  ▲
              │  └──────────── (aprovado: retoma com a chamada exata)
              ▼  │
       WAITING_APPROVAL ──► (rejeitado: o LLM é informado e responde) ──► COMPLETED
              │
              └──► EXPIRED

 RUNNING ──► FAILED            (erro não recuperável, LLM indisponível)
 RUNNING ──► BUDGET_EXCEEDED   (passos, tempo ou tokens)
 RUNNING ──► INTERRUPTED       (crash do backend, detectado na inicialização)
 QUEUED | RUNNING | WAITING_APPROVAL ──► CANCELLED
```

## 5. Exemplo de fluxo ponta a ponta: "Se o container estiver travado, reinicie"

```
1. POST /api/v1/conversations/{id}/messages  {"content": "Se o demo-api estiver travado, reinicie"}
   → 202 Accepted {executionId: E1, status: QUEUED}

2. Orchestrator(E1): RUNNING
   Context Builder → ambiente "local" (DEV), serviços permitidos [demo-api, ...]
   LLM → propõe getContainerStatus(service="demo-api")
   Policy → READ_ONLY → ALLOW
   Executor → Docker API (via proxy) → {state: running, health: unhealthy, restarts: 0}
   Diagnóstico determinístico → achado: UNHEALTHY
   LLM → propõe getContainerLogs(service="demo-api", tail=200)
   Policy → ALLOW → Executor → logs (truncados + mascarados)
   LLM → "o processo está travado (deadlock no pool)" → propõe restartContainer(service="demo-api")
   Policy → HIGH_RISK → REQUIRE_APPROVAL
   Approval Service → cria Approval A1 {tool, params, hash, risco, impacto, justificativa, expira em 15 min}
   E1 → WAITING_APPROVAL      (a thread é liberada; nada fica bloqueado esperando)

3. GET /api/v1/executions/E1 → mostra o pedido de aprovação A1, com o texto explicativo

4. POST /api/v1/approvals/A1/decision {"decision": "APPROVE", "comment": "ok"}
   → verifica permissão APPROVE, expiração, uso único e hash dos parâmetros
   → Audit: APPROVAL_GRANTED por user=U1
   → E1 volta para RUNNING e executa restartContainer(demo-api) EXATAMENTE como aprovado
   → Executor: ToolExecution STARTED → Docker restart → SUCCEEDED
   → LLM → propõe getContainerStatus (verificação) → healthy
   → LLM → resposta final
   E1 → COMPLETED

5. GET /api/v1/audit-events?resource=container:demo-api
   → "U1 aprovou às 14:03; o agente pediu o restart porque o container estava unhealthy e os logs
      mostravam X; resultado: sucesso em 4,2 s"
```

## 6. Síncrono x assíncrono e quando entram Redis e RabbitMQ

### No MVP

- **Síncrono**: CRUD de ambientes e serviços, consultas (execuções, auditoria, ferramentas), login e
  a *decisão* de aprovação (a resposta é imediata, e a retomada da execução é assíncrona).
- **Assíncrono**: a execução do agente. Ela leva de segundos a dezenas de segundos (é dominada pela
  latência do LLM) e pode ficar pausada por minutos aguardando aprovação. Manter uma requisição HTTP
  aberta durante esse tempo seria frágil.
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

## 7. Memória e contexto (visão geral; o detalhe vem na V4)

| Tipo | O que é | Onde fica | Fase |
|---|---|---|---|
| Memória de conversa | Mensagens do usuário e do agente, e chamadas de ferramenta da conversa | PostgreSQL, com uma janela das últimas N mensagens enviada ao LLM | MVP |
| Contexto operacional | Ambiente atual, serviços, últimas ações e execuções, deploys e incidentes recentes | Consultado no PostgreSQL pelo Context Builder a cada execução | MVP (básico) → V4 |
| Dados persistentes | Tudo o que é fato do sistema (auditoria, execuções, aprovações) | PostgreSQL | MVP |
| Conhecimento e documentação | Runbooks, README dos serviços, postmortems | Começa como texto no banco com busca full-text do PostgreSQL. Vetores (pgvector) **só** se a busca textual se mostrar insuficiente. Veja a [ADR-007](adr/0007-sem-memoria-vetorial-no-mvp.md). | V4 |

Regra geral: **PostgreSQL é a fonte da verdade. Redis, quando entrar, guarda apenas dados efêmeros ou
derivados**, que podem ser perdidos sem prejuízo.

## 8. Multi-tenancy desde já (sem implementar SaaS agora)

A plataforma multi-tenant só vem na V5, mas **todas as tabelas de domínio terão `organization_id` desde
o MVP**, com uma única organização criada no seed. Custa quase nada agora e evita uma migração dolorosa
depois. Estratégia prevista: schema compartilhado com `organization_id`, filtro obrigatório na camada de
acesso a dados e **Row-Level Security do PostgreSQL** como defesa em profundidade. Veja a
[ADR-005](adr/0005-organization-id-desde-o-mvp.md).

## 9. Organização do código (módulos)

```
com.devopsaaas
 ├── identity       usuários, autenticação, papéis (organizações na V5)
 ├── environment    ambientes e serviços permitidos (allowlist)
 ├── conversation   conversas e mensagens
 ├── agent          orchestrator, context builder, execuções, orçamentos
 ├── llm            port LlmClient + adapters + contabilização de tokens e custo
 ├── tool           registry, policy engine, executor, aprovações
 │    └── docker    ferramentas Docker + cliente da Docker Engine API
 ├── audit          registro e consulta de auditoria
 └── shared         erros, contexto de tenant, observabilidade, utilitários
```

As regras de dependência (por exemplo, "`tool` não depende de `agent`" e "`llm` não conhece JPA") serão
verificadas por testes de arquitetura, para que as fronteiras não se degradem com o tempo.

## 10. Stack e justificativa por fase

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
| Cliente da Docker Engine API | Integração Docker | MVP | Proposta: chamar a **API REST do Docker Engine** diretamente com o `RestClient` do Spring, através do proxy. É uma superfície pequena, fácil de testar com WireMock e sem dependência pesada. Alternativa: a biblioteca `docker-java`. |
| Provedor de LLM com tool calling | IA | MVP | **Pendente da sua escolha** (veja as perguntas abertas). |
| RabbitMQ | Mensageria | V2 | Seção 6. |
| Redis | Cache, locks, rate limit | V4/V5 | Seção 6. |
| OpenTelemetry | Tracing | V3 | |
| OAuth2/OIDC externo, API keys | SaaS | V5 | |

## 11. Perguntas em aberto (decisões pendentes)

1. **Provedor de LLM**: API comercial com tool calling ou modelo local (via Ollama, por exemplo)? Isso
   muda custo, privacidade e qualidade do tool calling. Com modelos locais pequenos, a qualidade do tool
   calling pode ser bem inferior. Não tenho certeza de como está hoje e precisaria testar.
2. **Versões de Java e Spring Boot**: confirmar no momento do setup.
3. **Onde o Docker roda** (Linux nativo, Docker Desktop no macOS/Windows ou WSL2): isso afeta o caminho
   do socket e o proxy.
4. **Idioma**: documentação em português e código (identificadores, commits) em inglês?
