# Fatia 3 — Docker real: proxy + adapter + demo-api + list/inspect/logs

> Status: **implementada** (2026-09-27). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 3.

## Objetivo

Provar que o isolamento do Docker funciona na prática e que as ferramentas do domínio operam um runtime real
sem conhecer o Docker. Ficam de fora o diagnóstico com achados (fatia 5) e o restart (fatia 8).

```
Backend ──► ContainerRuntime (port) ──► DockerEngineContainerRuntime ──► docker-socket-proxy ──► Docker Engine ──► demo-api
            tool.container              integration.docker              rede interna, sem porta     socket só aqui
```

## Decisões aprovadas

| # | Decisão |
|---|---|
| 1 | Trocar o proxy para o `linuxserver/socket-proxy`, fixado por digest ([ADR-011](../adr/0011-linuxserver-socket-proxy.md)). `ALLOW_RESTARTS` fica desligado até a fatia 8; `stop`/`kill` entram como risco residual. |
| 2 | O `ContainerRef` carrega o `connectionRef`; as conexões ficam em `devops.runtime.connections.<nome>.docker-url` |
| 3 | Stub HTTP no `HttpServer` do JDK no lugar do WireMock, somado aos testes com Docker real |
| 4 | Docker API fixada em `v1.44`, configurável |
| 5 | `connectivity-check` e `services/status` fora do executor e da auditoria: são leituras do usuário pela API, não chamadas de ferramenta do agente |
| 6 | `demo-api` em Spring Boot; o `OOMKilled` real não é garantido e fica documentado |
| 7 | Três testes com Docker real (intruso, variáveis de ambiente, segredos no log) e os riscos A e B registrados. Risco B: opção 2 (sem denylist), registrado como **risco residual aceito, não propriedade de segurança** |

## Estrutura

```
tool/container/     port ContainerRuntime (+ version), ContainerRef (+ connectionRef), TargetResolver (+ resolveAll),
                    EnvironmentRuntimeStatus (connectivity-check, services/status)
tool/builtin/       ListContainersTool, GetContainerStatusTool, GetContainerLogsTool
tool/web/           EnvironmentRuntimeController
integration/docker/ DockerEngineContainerRuntime, DockerInspect (só campos do domínio), DockerLogDecoder,
                    RuntimeConnectionProperties
demo-api/           módulo Maven novo: API mínima com endpoints de caos
scripts/            check-compose-docker-socket.sh (novo)

tool ──► port ◄── integration.docker         tool nunca depende de integration (ArchUnit)
```

## As duas camadas

O proxy e a allowlist protegem coisas diferentes, e a fatia prova as duas separadamente:

| Camada | Limita | Protege contra | Não protege contra |
|---|---|---|---|
| **Allowlist** (backend) | **Quais containers** o agente pode usar | O LLM escolher um alvo arbitrário, inclusive por prompt injection | Um backend comprometido |
| **Proxy** | **Quais operações** da API passam | Um backend comprometido criar containers, executar comandos, apagar recursos | Leitura (`inspect` com `Env`, logs) de **qualquer** container por um backend comprometido |

> A allowlist é uma garantia do backend contra o agente; não é uma barreira de segurança contra um backend
> comprometido.

## Como cada garantia foi implementada

| Garantia | Implementação | Prova |
|---|---|---|
| O backend não tem acesso ao socket | Só o `docker-socket-proxy` monta `/var/run/docker.sock` (`:ro`) | `scripts/check-compose-docker-socket.sh` no CI; na demonstração, `ls /var/run/docker.sock` falha dentro do backend |
| Proxy sem porta publicada, numa rede interna | Sem `ports:`; rede `docker-proxy` com `internal: true`, só com o backend | Mesmo script no CI; na demonstração, `curl localhost:2375` falha no host |
| O proxy recusa tudo além de ler containers e logs | `POST=0`, `ALLOW_RESTARTS=0`, `EVENTS=0`; container read-only, `cap_drop: [ALL]`, `no-new-privileges` | `RealDockerIT.theProxyRefusesEverythingBeyondReadingContainersAndLogs`: `create` privilegiado, `exec`, `start`, `restart`, `kill`, `delete`, `archive`, `export`, `info`, imagens, volumes, redes e eventos → `403` |
| O proxy do teste é o do Compose | Mesma imagem com digest e mesmas variáveis | `RealDockerIT.theProxyHereIsTheOneFromCompose` |
| As ferramentas passam pelo proxy de verdade | O `RealDockerIT` liga o adapter real e aponta a conexão `local` para o proxy | O **log de requisições do próprio proxy** contém as chamadas das ferramentas (`theToolsTalkToTheRealAdapter_throughTheProxy`) |
| **1. Container fora da allowlist** | `listContainers` pergunta ao runtime só pelos containers da allowlist, por nome exato; `ContainerRef` só nasce do `TargetResolver` | `RealDockerIT.aContainerOutsideTheAllowlist_isNeverListedNorResolvable_andTheProxyNeverSeesARequestForIt`: um container intruso real roda ao lado do alvo. Ele não aparece no `listContainers`; pedir por ele pelo nome lógico, pelo nome real ou pelo ID dá `RESOURCE_NOT_ALLOWED`; e o log do proxy **não tem nenhuma requisição** com o nome ou o ID dele |
| O proxy sozinho não isolaria | — | `RealDockerIT.theProxyItselfDoesNotIsolateContainers`: o mesmo proxy serve o `inspect` do intruso, com o `Env`, a quem pedir diretamente (risco A registrado como teste) |
| **2. Variáveis de ambiente não saem do adapter** | `DockerInspect` não declara `Config.Env`, `Cmd`, `Mounts` nem `Labels`; o parser pula esses campos | `RealDockerIT.theTargetsEnvironmentVariables_neverReachTheOutputOrTheDatabase` (container real com `DB_PASSWORD`), `DockerEngineContainerRuntimeTest.inspect_mapsOnlyDomainFields…` (resposta gravada com `DB_PASSWORD`) e `theInspectModel_hasNoFieldForEnvironmentCommandOrMounts` |
| **3. Segredos do log são mascarados** | O executor sanitiza e mascara a saída de qualquer ferramenta antes de gravar (fatia 2) | `RealDockerIT.secretsInTheRealLog_areMaskedBeforeAnythingIsStored`: o container real imprime um JWT e um `password=…`; a coluna `output` no banco só tem `<redacted…>`. Com o fake: `ContainerToolsIT.getContainerLogs_masksSecretsBeforeOutputLeavesExecutor` (inclui um caractere bidi, que vira `<U+202E>`) |
| O alvo é a entrada da allowlist, nunca o valor cru | O adapter usa o `containerName` cadastrado pelo admin, dentro de uma variável de caminho do `RestClient` | `ContainerToolsIT.theTargetIsTheAllowlistEntry_neverTheRawValue`: o nome real do container, `postgres`, `docker-socket-proxy` → `RESOURCE_NOT_ALLOWED`; `../demo-api`, `demo-api/json`, `demo-api?all=1` → `INVALID_ARGUMENTS` |
| Restart negado nesta fatia | `ALLOW_RESTARTS=0` | `RealDockerIT.restartIsRefusedByTheProxyInThisSlice…`: o adapter recebe `403` e devolve `FORBIDDEN` |
| Logs multiplexados e com TTY | `DockerLogDecoder`: frames de 8 bytes separados por stream, com buffer por linha; texto puro com TTY | Respostas gravadas do Docker 29.3.1 (`logs-multiplexed.bin`, `logs-tty.bin`) e `DockerLogDecoderTest` (linha partida entre frames, frame incompleto, linha sem timestamp, corte sem quebrar um par surrogate) |
| Leituras limitadas | Resposta limitada a 1 MiB; linhas cortadas em 2000 caracteres; `tail` de 1 a 500; `since` de no máximo 24 h | `logs_areCappedInBytes_andLongLinesAreCut`, `getContainerLogs_boundsItsArguments` |
| Timeouts abaixo dos das ferramentas | Conexão 2 s, leitura 8 s (ferramentas: 10 s e 15 s); sem proxy HTTP e sem seguir redirecionamentos | `slowResponse_endsAsUnavailable_withinTheReadTimeout` (resposta de 3 s com timeout de 300 ms) |
| Erros viram categorias, sem mensagens do Docker | `404` → `NOT_FOUND`, `403` → `FORBIDDEN`, `5xx`/conexão/timeout → `UNAVAILABLE`, resto → `UNEXPECTED` | `httpErrors_becomeCategories_withoutDockerMessages`, `unreachableProxy_isUnavailable`, `unknownConnection_isUnavailable_andNothingIsCalled` |
| Runtime indisponível: retentativa e falha limpa | O executor da fatia 2 repete ferramentas read-only em `UNAVAILABLE` | `ContainerToolsIT.anUnavailableRuntime_isRetried_andThenFails` (4 tentativas → `RUNTIME_UNAVAILABLE`) |
| Fronteiras | ArchUnit: `tool` não depende de `integration`; o adapter só conhece o port; só `integration` usa cliente HTTP | `ArchitectureTest` |
| RF-12 | `connectivity-check` (`ENVIRONMENT_MANAGE`) e `services/status` (`EXECUTION_READ`, só estado e health) | `EnvironmentRuntimeIT` (alcançável e inalcançável, permissão, `503`, `404` para outra organização) e `RealDockerIT.connectivityCheck_reachesTheRealEngine` |

## Testes

`./mvnw verify` → backend: **105** unitários/arquitetura + **89** de integração; demo-api: **2**. No total,
**196** testes (eram 143), **0 achados** do SpotBugs + FindSecBugs nos dois módulos. Os 10 testes do
`RealDockerIT` usam Docker real e rodam no job `build-test` do CI (Linux).

## Demonstração

Rodei a stack completa com `docker compose up` (imagens montadas a partir dos JARs do host; veja a divergência
10) e o roteiro abaixo.

**Isolamento:**

| Verificação | Resultado |
|---|---|
| Quem monta o socket | só `docker-socket-proxy` |
| `ls /var/run/docker.sock` dentro do backend | `No such file or directory` |
| `curl 127.0.0.1:2375` no host | sem conexão; o proxy não tem porta publicada |
| Redes | backend: `backend` + `docker-proxy`; proxy: só `docker-proxy` |
| Simulando um backend comprometido (`wget` de dentro do container do backend) | `GET /containers/json` → `200`; `POST /containers/create` privilegiado → **`403`**; `POST /containers/devops-demo-api/restart` → **`403`** |
| Backend → `demo-api` direto | `bad address`: o backend só alcança o `demo-api` pela API do Docker |

**API:**

| Passo | Resultado |
|---|---|
| `POST /environments/{id}/connectivity-check` | `{"reachable":true,"engineVersion":"29.3.1","apiVersion":"1.54","latencyMs":88}` |
| `GET /environments/{id}/services/status` | `demo-api` `RUNNING` / `HEALTHY` |
| `GET /tools?environmentId=…` | `listContainers`, `getContainerStatus`, `getContainerLogs`: `READ_ONLY`, sem aprovação |
| `POST /chaos/unhealthy` | `RUNNING` / `UNHEALTHY` |
| `POST /chaos/crash?code=42` | `EXITED` (o `inspect` mostra `ExitCode=42`) |
| `POST /chaos/oom` | `EXITED`, `ExitCode=3`, **`OOMKilled=false`**, como previsto |

As chamadas de ferramentas contra o Docker real ficam provadas pelo `RealDockerIT`: o loop do agente, que as
dispara a partir de uma pergunta, chega na fatia 4.

## Divergências e achados

1. **Risco residual novo: o websocket attach passa pelo proxy** (TM-B6-08). `GET /containers/{id}/attach/ws` é
   um `GET`, e o proxy deixa passar. Testei: com ele, um cliente consegue escrever no stdin de um container
   criado com stdin aberto. O backend nunca chama esse endpoint. O CI agora recusa `stdin_open: true` no
   Compose, e um teste falha se uma versão futura do proxy passar a bloquear. Não muda a arquitetura; está na
   ADR-011 e no documento 06.
2. **O proxy não filtra por container** (TM-B6-04, risco A): confirmado no `haproxy.cfg` da imagem. Virou
   teste (`theProxyItselfDoesNotIsolateContainers`) e texto explícito nos documentos 03 e 06 e na ADR-011.
3. **`:ro` no socket não protege** (TM-B6-05): confirmado. Com o socket montado como `:ro`, um restart pela API
   funcionou quando liberado no proxy.
4. **Além do desenho, no proxy:** `EVENTS=0` (o stream de eventos mostra a atividade de todos os containers do
   host, e nenhuma ferramenta usa) e o endurecimento do container (read-only, `cap_drop: [ALL]`,
   `no-new-privileges`). Testei os três com a imagem real antes de adotar.
5. **O script do Compose verifica mais que o previsto:** além de "só o proxy monta o socket" e "o proxy não
   publica portas", ele exige o socket `:ro`, `POST=0`, a rede `docker-proxy` interna e nenhum serviço com
   stdin aberto. Testei com um override que viola cada regra.
6. **`listContainers` faz um `inspect` por serviço** em vez de `GET /containers/json` com filtro de nome. O filtro
   de nome do Docker casa por substring/expressão regular; assim, nada fora da allowlist é sequer perguntado,
   e o `RestartCount` vem junto. A allowlist de um ambiente é pequena.
7. **`since` sem segundos:** o limite de 24 h fica declarado no próprio `@Pattern` (`1m`–`1440m`, `1h`–`24h`),
   e aparece no JSON Schema publicado.
8. **`redactedCount` saiu da saída de `getContainerLogs`:** quem mascara é o executor, depois da ferramenta. A
   contagem existe na métrica `devops.tool.output.redactions`. Documento 05 atualizado.
9. **Um container parado continua com o último health:** depois do `/chaos/crash`, o Docker mostra
   `EXITED` com health `UNHEALTHY`. As regras de diagnóstico da fatia 5 precisam olhar o estado antes do health.
10. **Build das imagens no ambiente em que desenvolvi:** o proxy TLS desse ambiente quebra o Maven dentro do
    `docker build` (como na fatia 0). Montei as imagens da demonstração a partir dos JARs do host, com o mesmo
    estágio de runtime. O job `image` do CI constrói os **dois** Dockerfiles reais (backend e `demo-api`).
11. **Nos testes, o proxy tem porta publicada:** o Testcontainers mapeia a porta do proxy para uma porta
    aleatória do host de testes, só durante o `RealDockerIT`. No Compose continua sem porta.
12. **`connectivity-check` só para ambientes ativos:** um ambiente desativado responde `404`, como nos outros
    pontos que usam o `EnvironmentDirectory`. `services/status` responde `503` com o runtime indisponível e
    `502` quando o proxy recusa ou responde algo inesperado.
13. **SpotBugs (3 achados no backend, 4 no `demo-api`):**
    - `IMPROPER_UNICODE` (2): removi o `toLowerCase` do mapeamento de estados; o Docker devolve valores em
      minúsculas ASCII, e qualquer outro valor vira `UNKNOWN`;
    - `CRLF_INJECTION_LOGS` (1): suprimido no método de chamada do adapter, porque o valor logado é um literal
      da própria classe;
    - no `demo-api`, `UC_USELESS_OBJECT` no `/chaos/oom` (corrigido guardando as alocações num campo) e três
      supressões que se mostraram desnecessárias (removidas).
14. **Fixtures gravadas:** as respostas do stub foram capturadas do Docker 29.3.1 através do proxy
    (`backend/src/test/resources/docker-api`). Os valores "secretos" são falsos e marcados para o gitleaks. O
    `.gitattributes` marca os `.bin` como binários, para o CRLF do log com TTY sobreviver.

## Pendente de verificação na sua máquina

O Docker Desktop com WSL2: pelo que sei, ele expõe `/var/run/docker.sock` para os containers Linux, e o proxy
depende disso. Não consigo testar esse ambiente daqui. Se `connectivity-check` responder `reachable: false`,
esse é o primeiro ponto a olhar.
