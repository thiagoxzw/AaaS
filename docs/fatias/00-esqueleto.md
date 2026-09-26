# Fatia 0 — Esqueleto executável

> Status: **implementada** (2026-09-26). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 0.

## Objetivo

Provar a cadeia **Maven → Spring Boot → PostgreSQL → Flyway → Testcontainers → imagem Docker → Docker
Compose → Prometheus → CI**, e nada além disso. Não há autenticação, ferramentas, LLM nem lógica do
agente.

## Versões

| Item | Versão | Como foi verificada |
|---|---|---|
| Java | 25 (LTS) | Build, testes, JaCoCo 0.8.15, ArchUnit 1.5.1 e SpotBugs 4.10.4 funcionaram com Java 25. O plano B (Java 21) **não foi necessário**. |
| Spring Boot | 4.1.1 | A versão mais recente no Maven Central. O baseline dele é Java 17 (`java.version` no starter-parent). |
| Maven (wrapper) | 3.9.16 | A 3.9.x mais recente no Maven Central |
| PostgreSQL | 18.6-alpine | Compose e Testcontainers usam a **mesma** imagem |
| Prometheus / Grafana | v3.15.0 / 13.2.2 | Tags do Docker Hub |
| Trazidas pelo Boot | Hibernate 7.4, Jackson 3.1, Flyway 12.4, Testcontainers 2.0.5 | `spring-boot-dependencies` 4.1.1 |

## Decisões de desenho (aprovadas antes do código)

| Decisão | Motivo |
|---|---|
| Actuator na porta **8081, não publicada** | As métricas e o health não ficam expostos quando a autenticação chegar. Só o Prometheus alcança essa porta, pela rede interna. |
| API na 8080, publicada só em `127.0.0.1` | RNF-SEG-15 |
| Logs JSON nativos do Spring Boot (formato ECS) | Sem biblioteca extra |
| `RequestIdFilter`: `X-Request-Id` → MDC `requestId` | Correlação de logs. Um valor vindo do cliente **só é reaproveitado se tiver um formato estrito**; qualquer outro valor é substituído, e isso impede injeção de conteúdo nos logs via header. |
| Flyway `V1__create_organization.sql` | Prova o fluxo de migração. Os IDs são gerados pela aplicação (documento 04). |
| Surefire (`*Test`) + Failsafe (`*IT`) | Separa os testes rápidos dos testes com container |
| Imagem multi-stage, runtime `eclipse-temurin:25-jre-alpine`, usuário `10001` | Imagem pequena e não-root (RNF-OPS-03) |
| Camadas do Spring Boot (`-Djarmode=tools extract --layers`) | As dependências mudam pouco, o código muda a cada commit, e o cache aproveita isso |
| Segredos no Compose como `${VAR:?}` | Sem `.env`, o Compose **se recusa** a subir, em vez de usar uma senha padrão |
| CI com `contents: read` | Menor privilégio para o `GITHUB_TOKEN` |

## O que foi construído

```
pom.xml                                   agregador + parent (Spring Boot 4.1.1, Java 25, enforcer, JaCoCo, SpotBugs)
mvnw, mvnw.cmd, .mvn/wrapper/             Maven Wrapper 3.9.16
backend/
  Dockerfile                              multi-stage, camadas, não-root
  src/main/java/com/devopsaaas/
    DevopsAgentApplication.java
    shared/observability/RequestIdFilter.java
  src/main/resources/
    application.yml                       porta 8080, management 8081, logs ECS, histograma HTTP
    db/migration/V1__create_organization.sql
  src/test/java/com/devopsaaas/
    ApplicationStartupIT.java             PostgreSQL real, Flyway, portas, métricas, requestId
    ArchitectureTest.java                 sem ciclos entre módulos; shared não depende de outros módulos
    shared/observability/RequestIdFilterTest.java
docker-compose.yml                        postgres, backend, prometheus, grafana (tudo em 127.0.0.1)
observability/                            scrape do Prometheus, datasource e dashboard do Grafana
scripts/check-compose-ports.sh            falha se alguma porta for publicada fora de 127.0.0.1
.github/workflows/ci.yml                  build+testes+análise, validação do compose, secret scan, imagem
.github/dependabot.yml                    Maven, GitHub Actions, Docker
.gitattributes                            mvnw e *.sh sempre com LF (checkout no Windows/WSL2)
```

## Testes

| Teste | O que prova |
|---|---|
| `ApplicationStartupIT.healthIsUp_onManagementPort` | A aplicação sobe com PostgreSQL real (Testcontainers) |
| `ApplicationStartupIT.actuatorIsNotExposed_onApiPort` | `/actuator/*` responde **404** na porta da API |
| `ApplicationStartupIT.prometheusMetricsAreExposed_onManagementPort` | As métricas estão disponíveis para o Prometheus |
| `ApplicationStartupIT.flywayCreatesOrganizationTable` | O Flyway aplicou a migração |
| `ApplicationStartupIT.everyApiResponseCarriesRequestId` | O filtro de correlação está ativo |
| `RequestIdFilterTest` (4 casos) | Gera o id quando falta, reaproveita um válido, **substitui um que tentaria injetar conteúdo no log** e limpa o MDC |
| `ArchitectureTest` (2 regras) | Fronteiras dos módulos |

Resultado local: `./mvnw verify` → 6 testes unitários e 5 de integração passando, **0 achados** do
SpotBugs + FindSecBugs.

## Roteiro de demonstração (executado)

```bash
cp .env.example .env            # e troque as senhas
docker compose up -d --build
```

| Passo | Comando | Resultado observado |
|---|---|---|
| 1 | `curl localhost:8080/actuator/health` | `404`: o Actuator não está na porta da API |
| 2 | `docker compose exec backend wget -qO- localhost:8081/actuator/health` | `{"groups":["liveness","readiness"],"status":"UP"}` |
| 3 | `curl localhost:8081/actuator/health` (do host) | conexão recusada: a porta não é publicada |
| 4 | `docker compose exec backend id` | `uid=10001(app)` |
| 5 | `curl -H "X-Request-Id: demo-slice-0001" localhost:8080/nothing` | o header volta na resposta, e o `requestId` aparece nos logs JSON |
| 6 | Prometheus → Targets | `devops-agent up http://backend:8081/actuator/prometheus` |
| 7 | Grafana → Dashboards | pasta *DevOps Agent*, dashboard *DevOps Agent — Runtime* |
| 8 | `flyway_schema_history` | `1 | create organization | t` |

## Divergências e achados durante a implementação

Nenhum deles mudou a arquitetura, mas todos ficam registrados.

1. **O SpotBugs + FindSecBugs encontrou `SERVLET_HEADER`** (baixa severidade) no `RequestIdFilter`. O
   alerta é procedente em geral ("header pode ser alterado pelo cliente"). Aqui o header já é tratado como
   não confiável e validado por um formato estrito, o que é coberto pelo teste
   `replacesRequestId_whenHeaderCouldInjectIntoLogs`. Solução: `@SuppressFBWarnings` **só nesse método**,
   com a justificativa escrita. O threshold continua em `Low` para o resto do código.
2. **O ArchUnit detectou a nova dependência** do `shared` no pacote da anotação do SpotBugs. Ela foi
   permitida explicitamente, com um comentário: só existe em tempo de build.
3. **Correlação:** o desenho original do 07 citava `traceId`. Nesta fatia é `requestId` (filtro próprio),
   e o traceId do OpenTelemetry fica para a V3, como já estava na roadmap.
4. **Avisos do Maven em Java 25:** o Maven 3.9 emite avisos de `sun.misc.Unsafe` (do Guice interno dele).
   São inofensivos e não vêm do projeto.

### Limitações do ambiente de verificação (não do projeto)

A implementação foi verificada num ambiente de nuvem com proxy HTTPS próprio:

- **O Docker Hub respondeu `429`** (limite de requisições). As imagens foram baixadas pelo mirror público
  `mirror.gcr.io` e retagueadas localmente. O projeto continua referenciando as imagens oficiais.
- **O estágio de build do Dockerfile (Maven dentro do Docker) não pôde ser validado localmente:** o proxy
  do ambiente intercepta o TLS, e a imagem de build não confia na CA dele. A verificação local usou uma
  variante temporária, fora do repositório, que copia o jar compilado no host e mantém o estágio de
  runtime **idêntico**. **O estágio de build completo é validado pelo job `image` do CI.**

## Pendências registradas

- O Dependabot cobre o `Dockerfile`, mas não as imagens do `docker-compose.yml` (postgres, prometheus,
  grafana). Não verifiquei se ele já suporta Compose; enquanto isso, essas versões são atualizadas
  manualmente.
- As actions do GitHub estão fixadas por versão principal (`@v4`), não por SHA. Fixar por SHA é mais
  seguro contra comprometimento da cadeia de suprimentos, e fica como decisão a tomar no fechamento do
  MVP (fatia 9), junto com a varredura de imagens.
