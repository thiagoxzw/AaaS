# ADR-0011 — `linuxserver/socket-proxy` como proxy do socket do Docker

- **Status:** Aceita
- **Data:** 2026-09-27
- **Substitui:** a parte da [ADR-0003](0003-sem-shell-docker-via-proxy.md) que presumia um proxy capaz de
  liberar o restart sem liberar `create` e `exec`. O resto da ADR-0003 continua valendo.

## Contexto

A ADR-0003 decidiu que o backend acessa o Docker só por um proxy do socket, que libera apenas listar,
inspecionar, ler logs e (na fatia 8) reiniciar containers. O threat model partia da mesma premissa (TM-B6-01).

No desenho da fatia 3, testei essa premissa com o proxy mais conhecido, o `tecnativa/docker-socket-proxy`:

- ele libera `POST` com uma **chave global**. Para permitir o `POST /containers/{id}/restart`, eu teria que
  permitir também `POST /containers/create` e `POST /containers/{id}/start`;
- com isso, um backend comprometido poderia criar um container privilegiado com o disco do host montado, o
  que equivale a root no host.

Ou seja, com esse proxy a premissa da ADR-0003 é **falsa** justamente no ponto que importa.

## Decisão

Usar o **`linuxserver/socket-proxy` 3.4.5**, fixado por digest
(`sha256:ca6c0301a652232d02cb0ff9f1c5d20d9b339f391e1c1dffaae6ef3bfc59d009`). Ele é uma derivação do mesmo
desenho (HAProxy na frente do socket), mas tem chaves separadas para as ações sobre containers:
`ALLOW_RESTARTS`, `ALLOW_START`, `ALLOW_STOP` e outras.

Configuração da fatia 3 (a mesma no `docker-compose.yml` e no `RealDockerIT`):

| Variável | Valor | Por quê |
|---|---|---|
| `CONTAINERS` | `1` | `GET /containers/json` e `GET /containers/{id}/json` |
| `ALLOW_LOGS` | `1` | `GET /containers/{id}/logs` |
| `ALLOW_RESTARTS` | `0` | Nenhuma ferramenta reinicia antes da fatia 8: **menor privilégio por fatia** |
| `POST` | `0` | Nenhum `POST`, `PUT` ou `DELETE` genérico |
| `EVENTS` | `0` | O stream de eventos mostra a atividade de **todos** os containers do host; nenhuma ferramenta usa |
| `PING`, `VERSION` | `1` | `connectivity-check` (RF-12) |
| `DISABLE_IPV6` | `1` | Em hosts sem IPv6 (como o ambiente em que desenvolvi), o proxy não sobe sem essa opção; sem efeito de segurança |

E o container do proxy é endurecido: sistema de arquivos read-only (com `tmpfs` em `/run` e `/tmp`),
`cap_drop: [ALL]`, `no-new-privileges`, nenhuma porta publicada e só a rede `docker-proxy`, que é
`internal: true`.

### Verificado empiricamente (Docker Engine 29.3.1)

| Operação | Resultado |
|---|---|
| listar, inspecionar, logs, `version`, `_ping` | `200` |
| `create` (inclusive privilegiado), `exec`, `start`, `delete`, `kill`, `restart` (com `ALLOW_RESTARTS=0`), `archive`, `export`, `info`, imagens, volumes, redes, eventos | `403` |
| `stop` e `kill` com `ALLOW_RESTARTS=1` | passam: fazem parte da mesma chave (risco residual da fatia 8) |
| `GET /containers/{id}/attach/ws` (websocket) | **`101`**: passa (risco residual, abaixo) |

Os `403` são verificados a cada CI em `RealDockerIT.theProxyRefusesEverythingBeyondReadingContainersAndLogs`, com
o proxy real, a mesma imagem e a mesma configuração do Compose.

## O que o proxy protege, e o que ele **não** protege

O proxy e a allowlist são **duas camadas diferentes**:

```
LLM ──► Backend (política + allowlist) ──► Proxy ──► Docker API
            limita QUAIS containers         limita QUAIS operações
            o agente pode usar              qualquer cliente pode fazer
```

- **O proxy limita capacidades da API.** Mesmo um backend comprometido não cria containers, não executa
  comandos dentro deles, não apaga nada e não mexe em imagens, volumes ou redes.
- **O proxy não isola containers.** As regras dele são por padrão de caminho
  (`/containers/[a-zA-Z0-9_.-]+/...`), sem filtro por nome ou label. Conferi isso no `haproxy.cfg` da própria
  imagem. Um backend comprometido consegue **ler** o `inspect` (inclusive `Config.Env`) e os logs de
  **qualquer** container do host. O teste `RealDockerIT.theProxyItselfDoesNotIsolateContainers` registra
  exatamente isso.
- **A allowlist é uma garantia do backend contra o agente**, não uma barreira contra um backend
  comprometido. Ela impede que o LLM escolha um alvo arbitrário (`ContainerRef` só nasce do
  `TargetResolver`), e isso é testado com um container "intruso" real ao lado do alvo.

## Riscos residuais

1. **Leitura de qualquer container por um backend comprometido** (inspect, com `Env`, e logs). Aceito no
   MVP: a arquitetura protege contra o abuso do agente, não resolve o comprometimento completo do backend.
   Revisitar na V7 (agente remoto por host, com a allowlist aplicada do lado do host).
2. **Websocket attach passa pelo proxy.** `GET /containers/{id}/attach/ws` é um `GET`, e o proxy deixa passar.
   Testei: com isso, um cliente consegue **escrever no stdin** de um container que foi criado com stdin aberto
   (`-i`). O backend nunca chama esse endpoint. Mitigações:
   - o CI recusa `stdin_open: true` em qualquer serviço do Compose (`scripts/check-compose-docker-socket.sh`);
   - `RealDockerIT.websocketAttach_isNotBlockedByTheProxy_residualRisk` falha se uma versão futura do proxy
     passar a bloquear, para o threat model ser atualizado.

   Containers de fora do Compose, criados com `-i`, continuam expostos a um backend comprometido.
3. **`stop` e `kill` junto com o restart (fatia 8).** `ALLOW_RESTARTS=1` libera os três. Um backend
   comprometido poderia parar containers. É um risco de disponibilidade, não de escalada de privilégio.
4. **O admin pode colocar containers da plataforma na allowlist** (`docker-socket-proxy`, `backend`,
   `postgres`). É um **risco residual aceito, não uma propriedade de segurança**: a arquitetura impede o LLM de
   escolher um alvo arbitrário, mas não tenta proteger contra um administrador que deliberadamente configure
   um alvo privilegiado. Não há denylist no MVP.

## Alternativas consideradas

- **`tecnativa/docker-socket-proxy`:** recusado pelo `POST` global, como descrito acima.
- **Uma configuração própria de HAProxy ou nginx:** daria controle total, inclusive para bloquear o websocket
  e filtrar por nome. Mas seria um componente de segurança nosso, sem manutenção da comunidade. Fica como
  opção se os riscos residuais 1 ou 2 deixarem de ser aceitáveis.
- **Filtrar por nome no proxy:** a imagem não suporta. Filtrar por nome exigiria a configuração própria
  acima, e a lista de nomes teria que ser sincronizada com a allowlist do banco.
- **Docker rootless ou Podman:** reduz o impacto de um comprometimento no host, mas não substitui o filtro de
  operações e muda o ambiente de execução do usuário (Docker Desktop no Windows). Fora do escopo do MVP.

## Consequências

- (+) A premissa central da ADR-0003 volta a ser verdadeira: liberar o restart na fatia 8 **não** libera
  `create`, `start` nem `exec`.
- (+) Menor privilégio por fatia: na fatia 3 o proxy é só leitura.
- (+) Os limites e os riscos residuais estão escritos e testados, e não presumidos.
- (−) O proxy não isola containers. O threat model passa a dizer isso explicitamente.
- (−) Uma imagem a mais para acompanhar (fixada por digest; atualizar conscientemente).
