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

## Ordem até a release

```
9c (este PR, com o Tomcat 11.0.26) → CI verde → medição final de H2 → revisão final → tag v0.1.0 (quando o autor mandar)
```
