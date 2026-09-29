# Fatia 9c — Reprodutibilidade e release

> Status: **implementada** (2026-09-28), por decisão do autor: o Tomcat subiu para 11.0.26 (9c-01), e o CI
> bloqueia só CRITICAL com correção disponível (9c-02). Plano: [07 — Plano do MVP](../07-plano-do-mvp.md), fatia 9.

## Objetivo

Alguém que clone o repositório deve entender o produto sem ler 30 arquivos Java: subir o stack, ver o agente
diagnosticar, propor, esperar um humano, reiniciar, verificar e auditar. Esta etapa é quase só documentação e
reprodutibilidade. A medição final de H2 e a tag `v0.1.0` vêm depois, nessa ordem, e a tag só quando o autor
mandar.

## O que entrou

| Item | O que é |
|---|---|
| `scripts/demo.sh` | O roteiro canônico em 9 passos: login, ambiente e allowlist, quebrar o `demo-api`, perguntar, a aprovação (`system` × `agentClaims`), decidir, o que rodou e a verificação, a explicação da ação, a auditoria por recurso. `--yes` aprova sozinho; sem terminal, rejeita e diz como recuperar o `demo-api`. Com a OpenAI, se o modelo não propuser o restart, o script mostra a resposta e termina. Funciona no Git Bash e nunca imprime o token nem nada do `.env` |
| `scripts/evaluate-agent.sh` | Além da tabela para a avaliação humana, grava `evaluation-*.jsonl` com uma linha por cenário: commit (com `-dirty` se houver mudanças locais), provedor, modelo, versão do prompt, cenário, causa real, horário, custo, estado da execução e estado final, cada chamada com os argumentos gravados (`service`, `tail`, `since`), o `scope` dos logs lidos (`CURRENT_RUN`, `SINCE`, `ALL_RUNS`), o resultado, o motivo de negação e os achados. Os argumentos vêm de `GET /tool-executions/{id}`, já mascarados. Os achados também vêm da API: o script deixou de consultar o banco |
| CI: Trivy | O job `image` constrói as duas imagens e as varre com o `aquasec/trivy:0.74.0`. O resumo por severidade vai para a página do job (`scripts/trivy-summary.sh`), e os JSON viram o artefato `image-scan`, guardado mesmo quando o job falha. Depois, `scripts/trivy-gate.sh` aplica a política 9c-02 |
| README | Reescrito na estrutura: o que é, demonstração, arquitetura, fluxo de segurança, como executar, testes, threat model, decisões, limitações, roadmap |

**Demonstração executada** no compose (2026-09-28), com o provedor `scripted`:
- `scripts/demo.sh --yes`: `WAITING_APPROVAL` com as evidências `UNHEALTHY` e `RECENTLY_STARTED`, aprovação,
  restart `SUCCEEDED` com verificação `HEALTHY`, `docker says now: running/healthy`, explicação e auditoria
  completas;
- sem terminal: a aprovação foi `REJECTED`, o restart não rodou, e o `demo-api` continuou `unhealthy`.

**Avaliação executada** com a pergunta padrão e com a que pede restart. Na segunda, cada linha registra
`getContainerLogs(tail=20)→SUCCEEDED [CURRENT_RUN]` e `restartContainer()→WAITING_APPROVAL`, e o JSON traz
`finalStatus: CANCELLED`.

## Achado 9c-01: o que as imagens contêm

Varredura local com o Trivy 0.74.0 (2026-09-28) das imagens `devops-agent` e `devops-demo-api`. A imagem local
do backend usa a mesma base de execução e as mesmas dependências da imagem do CI; muda só o empacotamento do jar.
O resultado oficial é o do CI.

| Alvo | CRITICAL | HIGH | MEDIUM | LOW |
|---|---|---|---|---|
| Pacotes do sistema (Alpine 3.24.2), nas duas imagens | 0 | 0 | 0 | 0 |
| Dependências Java, nas duas imagens | **3** | 0 | 0 | 0 |

**As severidades são as do Trivy**, não a classificação oficial do Apache Tomcat. Segundo o autor, o Apache
classifica o CVE-2026-65182 como *Important* e os outros dois como *Low*. Não foi possível confirmar aqui,
porque o `tomcat.apache.org` é bloqueado pela política de rede deste ambiente.

As três são do `org.apache.tomcat.embed:tomcat-embed-core` **11.0.24**, a versão que o BOM do Spring Boot
4.1.1 fixa (`tomcat.version`). Todas estão corrigidas na **11.0.25**:

| CVE | Título (do banco do Trivy) |
|---|---|
| CVE-2026-65182 | Security constraint bypass due to improper access control |
| CVE-2026-65905 | Authentication bypass via limited replay attack in DIGEST authenticator |
| CVE-2026-68525 | Unauthorized resource access via FORM authentication bypass |

**Exposição:** a aplicação autentica por JWT *stateless* (Spring Security), sem autenticação DIGEST ou FORM do
Tomcat. Por isso é **provável** que as três não sejam exploráveis aqui. Mas não li os avisos completos, e isso
não foi verificado.

**Fatos verificados para a decisão:**
- o Tomcat 11.0.25 e o 11.0.26 estão publicados no Maven Central;
- o Spring Boot 4.1.1 ainda é o último 4.1.x lá;
- sobrescrever `<tomcat.version>` no `pom.xml` é o mecanismo do próprio BOM.

### Decisões do autor e correção

**9c-01, corrigido.** O `pom.xml` raiz sobrescreve `<tomcat.version>11.0.26</tomcat.version>`, a propriedade
do próprio BOM do Spring Boot. Isso vale para o backend e o `demo-api`. A exceção por "provavelmente não
explorável" não foi usada, porque existe versão corrigida. O override sai quando o Spring Boot passar a
gerenciar a 11.0.26 ou mais nova.

| Verificação | Resultado |
|---|---|
| `dependency:tree` | `tomcat-embed-core`, `-websocket` e `-el` em 11.0.26 |
| `./mvnw verify` (inclusive `RealDockerIT`, com Docker real) | 520 testes, 0 falhas, SpotBugs sem achados |
| Integração em ordem reversa | 162 testes, 0 falhas |
| Trivy nas duas imagens, reconstruídas | **0 vulnerabilidades de qualquer severidade**, sistema e Java |
| `scripts/trivy-gate.sh` | Sai com 0 nos relatórios novos e com 1 nos antigos (as 3 corrigíveis nas duas imagens) |
| Compose com as imagens novas | O jar do backend traz `tomcat-embed-core-11.0.26.jar`; `scripts/demo.sh --yes` fechou o fluxo com `HEALTHY` |

**Achado de processo:** a primeira reconstrução local, com `package` incremental, manteve dentro do jar
executável as dependências antigas (11.0.24), mesmo com a árvore já em 11.0.26. Os testes não foram afetados,
porque usam o *classpath* do Maven. Só um `clean package` gerou o jar certo. A segunda varredura pegou isso. O
CI não tem o problema: o `docker build` compila do zero.

**9c-02, política do CI:**

| Resultado da varredura | CI |
|---|---|
| CRITICAL com correção disponível | **falha** |
| CRITICAL sem correção | reporta |
| HIGH, MEDIUM, LOW | reporta |

O resumo e o artefato são gerados antes da verificação, então o relatório fica disponível mesmo quando ela
falha.

## Achado 9c-03: CI da `main` vermelho no teste de OOM com Docker real

O CI da `main` após o merge da 9c (run 42) falhou em `RealDockerIT.realContainers_produceTheExpectedFindings`.
O contêiner que estoura o limite de 16 MB saiu com o código 137, mas o Docker do runner não marcou
`OOMKilled`. Por isso o backend produziu `KILLED_BY_SIGKILL` ("may have been an out-of-memory kill... not
conclusive"), e o teste esperava `OOM_KILLED`. O mesmo código tinha passado no CI do PR (run 41) e em todas as
execuções desde a fatia 5.

Aqui (Docker 29.3.1, cgroup v1), 30 repetições saíram todas com `137/true`. O runner `ubuntu-24.04` usa
cgroup v2. A causa dentro do Docker não foi verificada; não há fonte confirmada aqui.

**Decisão do autor:** o teste lê o estado do contêiner direto do Docker, fora da API do backend. Com
`OOMKilled=true`, exige `OOM_KILLED`. Com `false`, exige `KILLED_BY_SIGKILL`. Nos dois casos exige o código 137 e
que o `getContainerStatus` repita a marca e o código que o Docker dá. A regra "137 com a marca → `OOM_KILLED`"
continua provada no `ContainerDiagnosticsTest`, sem Docker.

**Limitação:** o teste com Docker real não garante que todo runner com cgroup v2 produza a marca de OOM. Ele
garante que o backend classifica fielmente o que o Docker reporta e nunca deduz um OOM que o Docker não marcou.

## Medição final de H2 (na máquina do autor)

A mesma avaliação das medições anteriores, com o modelo real. Ela não roda no CI: exige a chave, gasta dinheiro e
mede o modelo, não o código.

1. No `.env`: `LLM_PROVIDER=openai`, `OPENAI_API_KEY`, `LLM_MODEL`, os dois preços e `LLM_DAILY_BUDGET_USD`.
2. `docker compose up -d --build` na `main` atualizada, sem mudanças locais (o commit sai sem `-dirty`).
3. `scripts/evaluate-agent.sh`, com a pergunta padrão, só de diagnóstico, nos mesmos 5 cenários.
4. Preencher a coluna "Causa correta?" com Sim, Parcial ou Não, como antes.
5. Guardar o `.md` e o `.jsonl` de `evaluations/`. Eles **não** contêm a chave nem valores do `.env`; mesmo
   assim, confira antes de compartilhar.

**Comparação com as medições anteriores:** os dados brutos permitem comparar o que o modelo pediu (`since`,
`tail`, `scope`), o que a política decidiu e como cada execução terminou. **Diferenças não devem ser atribuídas
a uma causa única:** entre a primeira medição e a final mudaram os dados (fatia 6.1), a descrição das
ferramentas e o próprio modelo pode ter mudado.

### Resultado (2026-09-29)

Duas rodadas seguidas na máquina do autor, com o mesmo modelo da 6.1 (`gpt-5.6-luna`), a mesma pergunta, os
mesmos 5 cenários e a `main` em `964ac09`, sem mudanças locais. A versão do prompt é a `agent-system-v2`,
segundo o autor. A coluna "Causa correta?" é do autor, com o
[critério da 6.1](06-1-dados-atuais.md#critério-de-avaliação), sem transformar os casos numa taxa de acerto.

O `.md` e o `.jsonl` ficaram na máquina do autor, porque `evaluations/` não é versionado. **Os números abaixo são
os informados pelo autor**; não foram conferidos a partir dos arquivos.

| Cenário | 1ª medição | 6.1, rodada 1 | 6.1, rodada 2 | Final, rodada 1 | Final, rodada 2 |
|---|---|---|---|---|---|
| unhealthy | ✅ Sim | ✅ Sim | ✅ Sim | ✅ Sim | ✅ Sim |
| crash | ✅ Sim | ✅ Sim | ✅ Sim | ✅ Sim | ✅ Sim |
| oom | ✅ Sim (deslize) | ✅ Sim | ✅ Sim | ✅ Sim | ✅ Sim |
| kill | ❌ Não | 🟡 Parcial | 🟡 Parcial | 🟡 Parcial | 🟡 Parcial |
| stop | 🟡 Parcial | ✅ Sim | ✅ Sim | 🟡 Parcial | ✅ Sim |

**H2 final, consolidada pelo autor: 3 Sim, 2 Parcial, 0 Não.** O `stop`, que variou entre as duas rodadas, conta
como Parcial. Cada rodada, sozinha, deu 3 Sim e 2 Parcial (rodada 1) e 4 Sim e 1 Parcial (rodada 2).

- **`unhealthy`, `crash` e `oom`: Sim nas duas rodadas.** No `oom`, o modelo separou o OOM da JVM (código 3,
  `OutOfMemoryError: Java heap space`) de um OOM do kernel (`OOMKilled=false`). O deslize da primeira medição
  ("reiniciou uma vez") não apareceu.
- **`kill`: Parcial nas duas rodadas, como na 6.1.** O modelo identificou SIGKILL (137, sem `oomKilled`) e não
  inventou uma causa, mas não chegou ao `docker kill`. Os dados do backend não distinguem um `docker kill` de um
  `docker stop` que estourou o prazo (6.1).
- **`stop`: Parcial na rodada 1, Sim na rodada 2.** Na rodada 1, o modelo pediu histórico (`since: "2h"`) e pôs
  o OOM de uma execução anterior como causa principal do estado atual. Pelo critério da 6.1, uma causa errada
  como principal é Parcial. Na rodada 2, ele identificou `EXITED`, 143/SIGTERM, nenhum OOM e nenhum restart
  automático, e concluiu que o serviço foi interrompido de fora depois de iniciar, que é a causa real.

**Quando o modelo pediu `since`:** na rodada 1, no `unhealthy` e no `stop` (`"2h"`); os outros três ficaram em
`CURRENT_RUN`. Os dados da rodada 2 não foram informados.

**Custo:** cerca de US$ 0,006 na rodada 1 (US$ 0,00597, segundo o autor). O da rodada 2 não foi informado.

### Conclusão

O agente diagnosticou corretamente, e de forma consistente, os cenários `unhealthy`, `crash` e `oom`. O `kill`
continua parcialmente resolvido: o agente identifica SIGKILL, mas não determina a causa operacional específica.
O `stop` variou entre as rodadas, e a segunda voltou a identificar a parada por SIGTERM.

A medição confirma o que a 6.1 registrou. O padrão `CURRENT_RUN` reduz a contaminação por histórico, mas não
impede que o modelo peça histórico explicitamente. Quando ele pede, o histórico pode entrar no diagnóstico do
estado atual (o `stop` da rodada 1). A ferramenta fez o que foi pedido; é um limite de comportamento do agente,
não um defeito do backend.

**Decisão do autor:** a H2 está encerrada. O produto não muda só para melhorar a medição, porque não há
evidência suficiente que justifique outra alteração.

## Revisão final

Feita em 2026-09-29 sobre a `main` em `eaabd15`, com o escopo aprovado pelo autor: um clone limpo seguindo o
README, a documentação (links, âncoras, status, nomes de testes), a segurança (threat model × testes,
`.env.example`, padrões de configuração, gitleaks no histórico) e o código (TODO/FIXME, código morto,
configuração de teste em `main`), sem revisão linha a linha e sem funcionalidade nova. Todos os achados foram
corrigidos antes da tag, por decisão do autor.

| Achado | Evidência | Classificação | Correção |
|---|---|---|---|
| **R-01** O `.env.example` funcionava como senha padrão | Clone limpo, `cp .env.example .env`, compose `healthy`. O login com a senha do exemplo deu `200`, e um JWT assinado fora do sistema com o `JWT_SECRET` do exemplo foi aceito (`GET /me` → `200`; é preciso saber o UUID do usuário). As portas ficam em `127.0.0.1`, mas o README, o `.env.example` e a fatia 0 diziam "não há senha padrão" | **Bloqueava a release** (muda o modelo de segurança) | Os quatro segredos ficam vazios no `.env.example`. O `${VAR:?}` do Compose recusa valor vazio. O job "Compose validation" prova que a cópia sem preencher é recusada e preenche valores descartáveis antes de validar. O README mostra como gerar cada valor |
| R-02 | O README pedia só Docker com Compose; os scripts exigem bash, `curl` e `jq` | Não bloqueava | Requisitos completos no README |
| R-03 | Duas seções "Pendente de verificação" (`03-arquitetura.md`, `fatias/03-docker-real.md`) já estavam resolvidas | Não bloqueava | Marcadas como resolvidas, com a referência |
| R-04 | Documentos de fatias antigas citavam testes substituídos depois (`LlmConfigurationTest`, `RealDockerIT.restartIsRefusedByTheProxyInThisSlice…` e o antigo nome de `theProxyRefusesEverythingBeyondReadingContainersAndLogs…`) | Não bloqueava | Nota em cada um: substituído em qual fatia e por qual teste |
| R-05 | TM-B1-09 ("coberto pelos testes de auditoria") e TM-B8-06 ("—") não citavam testes; a TM-B1-09 citava um evento (`EXECUTION_CREATED`) que o código não tem | Não bloqueava | As duas linhas citam os testes reais, e a TM-B1-09 usa o evento real, `AGENT_EXECUTION_REQUESTED` |

**Verificado sem achado:** `scripts/demo.sh --yes` completo no clone limpo; 0 links ou âncoras quebrados em 34
arquivos `.md`; portas só em `127.0.0.1`; nenhum segredo padrão no `application.yml`; gitleaks sem achados nos 67
commits; nenhum TODO/FIXME; nenhuma classe de produção sem uso; nenhuma configuração só de teste em `main`.

**Limite:** a rede do ambiente da revisão bloqueia o Maven dentro do `docker build`. Por isso, as imagens do
clone limpo foram montadas com os jars do próprio clone e a mesma etapa final dos Dockerfiles. O `docker build`
completo é o do CI.

## Ordem até a release

```
9c (este PR, com o Tomcat 11.0.26) → CI verde → medição final de H2 → revisão final → tag v0.1.0 (quando o autor mandar)
```

Estado em 2026-09-29: a 9c está fechada (CI da `main` verde no run 44, depois da correção 9c-03), a H2 final
foi medida e a revisão final foi feita, com os achados corrigidos. A versão é a `0.1.0`; falta a tag.
