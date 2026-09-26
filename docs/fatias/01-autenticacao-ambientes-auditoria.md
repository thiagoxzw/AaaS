# Fatia 1 — Autenticação + Ambientes + Auditoria

> Status: **implementada** (2026-09-26). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 1.

## Objetivo

Provar **identidade, autorização por permissão, isolamento de tenant na consulta e auditoria imutável**.
Não entram ferramentas, agente nem LLM. Também ficam de fora API keys, múltiplas organizações, refresh
token e o `connectivity-check` (fatia 3).

## Decisões aprovadas antes do código

| # | Decisão |
|---|---|
| 1 | JWT **HS256** com a chave em `JWT_SECRET` (no mínimo 32 bytes, validado ao subir). O token só tem `iss`, `sub`, `iat` e `exp` (15 min), **sem papéis**. RS256 fica para a V5. |
| 2 | **UUIDv7 com implementação própria** (`shared.id.Ids`), gerado no construtor da entidade: o domínio não depende do ORM para ter identidade |
| 3 | Repositórios estendem só a interface mínima `Repository`: **não existem** `findById`/`findAll` sem organização |
| 4 | Limitador de login com **Caffeine** (versão fixada, porque o Boot 4.1.1 não a gerencia) |
| 5 | Campos JSON desconhecidos são **rejeitados** (`400`) em toda a API |
| 6 | Concorrência no `PATCH` pela `version` no corpo (`409` se estiver desatualizada) |
| 7 | A classe da tabela `environment_service` se chama `AllowlistedService`, e desativar um serviço gera `SERVICE_DISABLED` |
| 8 | O admin inicial é criado a partir de `ADMIN_EMAIL`/`ADMIN_PASSWORD`, e só quando não existe nenhum usuário |

## Como os módulos ficaram

```
identity     AppUser, Role, PermissionResolver, login, JWT, limitador, bootstrap do admin, SecurityConfiguration
environment  Environment, AllowlistedService, EnvironmentManagement, API de ambientes e allowlist
audit        AuditEvent (imutável), AuditRecorder (transação obrigatória), consulta paginada
shared       Ids (UUIDv7), CurrentUser, Permission, @PublicEndpoint, ApiException, problem+json, requestId

environment → audit, shared      identity → shared      audit → shared
```

Nenhum módulo depende do `identity`: todos os outros enxergam o usuário só como `CurrentUser`, com
**permissões**. O enum `Role` fica confinado ao `identity`.

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| Autorização recarregada do banco (RNF-SEG-13) | `UserReloadingJwtConverter` busca o usuário pelo `sub` a cada requisição; usuário desativado ou inexistente → `401` | `disabledUser_isRejected_evenWithAValidToken`, `removedRole_takesEffectOnTheNextRequest` |
| Negação por padrão (TM-B1-08) | só `/auth/login` é público; o resto exige token **e** `@PreAuthorize` com uma permissão | regra ArchUnit `endpoints_declare_their_authorization` + `AuthorizationMatrixIT` (endpoints × papéis) |
| Tenant na consulta (RNF-SEG-14) | repositórios com métodos como `findByIdAndOrganizationId`; o `organizationId` vem sempre do `CurrentUser` | regra ArchUnit `repositories_do_not_inherit_unscoped_finders` + `anotherOrganizationsEnvironment_isNotFound` |
| Tenant garantido pelo banco (documento 04, §5) | FKs compostas `(organization_id, …)` | `compositeForeignKey_rejectsAServicePointingToAnotherOrganizationsEnvironment` |
| `organization_id` em toda tabela | coluna `NOT NULL` desde as migrações | `everyDomainTable_hasANotNullOrganizationId` (lê o `information_schema`) |
| Auditoria na mesma transação | `AuditRecorder` com `Propagation.MANDATORY` | `recorder_refusesToRecordOutsideATransaction`, `auditEvent_rollsBackTogetherWithTheChangeItDescribes` |
| Auditoria imutável (RNF-SEG-09) | `@Immutable` no Hibernate + trigger que bloqueia `UPDATE`, `DELETE` e `TRUNCATE` | `auditEvents_cannotBeUpdatedDeletedOrTruncated_evenWithDirectSql` |
| Login sem enumeração de usuários (TM-B1-02) | resposta idêntica; usuário inexistente também roda o bcrypt contra um hash fixo | `login_failsIdentically_forUnknownUserAndWrongPassword` |
| Força bruta (RNF-SEG-17) | 5 falhas por e-mail ou 50 por IP em 15 min → `429` com `Retry-After`; cache limitado | `LoginThrottleTest` (relógio falso) + `login_isThrottled_afterFiveFailures_evenWithTheCorrectPassword` |
| Tokens forjados (TM-B1-01) | algoritmo fixado em HS256 e issuer validado | `tokenSignedWithAnotherKey_isRejected`, `unsignedToken_isRejected`, `expiredToken_isRejected` |
| Mass assignment (TM-B1-05) | `fail-on-unknown-properties` | `unknownFields_suchAsOrganizationId_areRejected` |
| Sem segredo no `connectionRef` (RF-13) | só aceita um nome lógico (`^[a-z0-9][a-z0-9-]{0,63}$`) | `connectionRef_cannotBeAUrl` |
| Segredos fora de logs (RNF-SEG-07a) | `toString()` mascarado em `JwtProperties`, `BootstrapProperties`, `LoginRequest`, `LoginResponse`, `LoginResult` e `AppUser` | `toString_neverContainsTheSecret` |

## Testes

`./mvnw verify` → **28** testes unitários e de arquitetura + **39** de integração, **0 achados** do SpotBugs
+ FindSecBugs.

Todos os testes de integração compartilham **um único container** PostgreSQL 18.6. Como a auditoria não pode
ser apagada (por desenho), os testes nunca limpam o banco: cada um se isola com nomes únicos e organizações
próprias.

## Demonstração (executada, stack criado do zero)

| Passo | Resultado observado |
|---|---|
| `docker compose up` | O Flyway aplica V1..V5, e o admin é criado a partir do `.env` |
| `POST /auth/login` | token `Bearer`, header `{"kid":"hs256-v1","alg":"HS256"}` |
| `GET /me` | todas as permissões do admin |
| `POST /environments` (`local`) | `201`, id UUIDv7, `version 0` |
| `POST /environments/{id}/services` (`demo-api`) | `201`, `enabled: true` |
| `GET /audit-events` | `ENVIRONMENT_CREATED` e `SERVICE_ALLOWLISTED`, com ator e detalhes |
| Sem token | `401` |
| Campo `organizationId` no corpo | `400` |
| `autonomyLevel: AUTOMATED` | `422` |
| Ambiente inexistente | `404` |
| 6º login após 5 falhas (com a senha certa) | `429`, `Retry-After: 899` |
| `DELETE FROM audit_event` direto no `psql` | `ERROR: audit_event is append-only: DELETE is not allowed` |
| Métricas | `devops_auth_login_total{outcome="failure"} 5`, `success 1`, `throttled 1` |

## Divergências e achados durante a implementação

1. **O JWT publicava um hash da chave secreta.** Sem configuração, o `NimbusJwtEncoder` usa como `kid` o
   thumbprint RFC 7638 da chave, que para uma chave HMAC é um SHA-256 do segredo. Confirmei que o `kid` do
   token batia com o hash calculado a partir do `JWT_SECRET`. O risco prático é baixo: um token HS256 já
   permite ataque offline pela assinatura, e uma chave aleatória de 48 bytes o torna inviável. Mesmo assim,
   era informação derivada do segredo sem necessidade. **Correção:** `kid` estático `hs256-v1`, que também
   prepara a rotação de chaves, mais o teste `issuedToken_doesNotExposeAnythingDerivedFromTheSecret`.
2. **Busca de auditoria com período nulo.** O padrão `(:from is null or …)` com data nula falha no
   PostgreSQL (`could not determine data type of parameter`). **Correção:** a consulta sempre recebe o
   período, e sem filtro o serviço usa limites abertos (1970 até 9999).
3. **`/actuator/*` na porta da API agora responde `401`**, e não mais `404`. É consequência do "negar por
   padrão": um anônimo não descobre nem se a rota existe. O teste da fatia 0 foi atualizado para provar as
   duas coisas: `401` sem token e `404` com token de admin.
4. **SpotBugs + FindSecBugs: 24 achados na primeira execução**, tratados um a um:

   | Achado | Tratamento |
   |---|---|
   | `DCN_NULLPOINTER_EXCEPTION` | corrigido: checagem explícita de nulo |
   | `SE_BAD_FIELD` | corrigido: `CurrentUser` é `Serializable` |
   | `SE_TRANSIENT_FIELD_NOT_RESTORED` | corrigido: os headers da `ApiException` viraram um tipo serializável |
   | `URF_UNREAD_FIELD` (3) | corrigido: getters no `AppUser` |
   | `UWF_UNWRITTEN_FIELD` em `AuditEvent.onBehalfOfUserId` | corrigido: o campo sai da entidade até a fatia 4. A coluna continua no banco. |
   | `UWF_UNWRITTEN_FIELD` em `Organization.slug` | suprimido no campo: é preenchido pelo JPA |
   | `SPRING_ENDPOINT` (10) | excluído em `spotbugs-exclude.xml`: é um inventário informativo, e a garantia real vem do ArchUnit + `AuthorizationMatrixIT` |
   | `SPRING_CSRF_PROTECTION_DISABLED` (2, High) | suprimido num método nomeado, `disableCsrfForStatelessApi`, citando o threat model (06, B1) |
   | `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION` (2) | suprimido: `HttpSecurity.build()` declara `throws Exception` |
   | `XSS_SERVLET` | suprimido: o corpo é uma constante `problem+json` |
   | `CRLF_INJECTION_LOGS` | suprimido: o valor logado é um UUID, e os logs são JSON |

   O alerta de CSRF apontava para o *lambda* `csrf -> csrf.disable()`, e a supressão no método que o contém
   não alcançava o lambda. Extrair um método nomeado resolveu e deixou a decisão explícita no código.
5. **Ajustes no modelo de dados** (documento 04 atualizado):
   - `environment_service` ganhou a coluna `version`, para o controle de concorrência do `PATCH`;
   - as ações de auditoria dos serviços são `SERVICE_ALLOWLISTED`, `SERVICE_UPDATED`, `SERVICE_DISABLED` e
     `SERVICE_ENABLED`;
   - a coluna `audit_event.action` não tem `CHECK` no banco: a lista cresce a cada fatia e é validada pelo
     enum da aplicação. Os `CHECK` ficam em `actor_type`, `outcome` e `resource_type`, que são estáveis.

### Limitações do ambiente de verificação

- Como na fatia 0, a imagem local foi montada a partir do jar compilado aqui, com o estágio de runtime
  idêntico. O build completo do Dockerfile é validado pelo job `image` do CI.
- O Docker daemon do ambiente de nuvem parou uma vez durante a sessão, e os testes com Testcontainers
  falharam por "Docker indisponível" até ele ser reiniciado. Não foi um problema do projeto.
