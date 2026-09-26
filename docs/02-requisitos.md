# 02 — Requisitos

> Status: **aceito** (2026-09-26). Cada requisito tem um ID estável para ser referenciado em testes, issues e ADRs.
> A coluna **Fase** indica quando o requisito entra (MVP, V1…V7, conforme a roadmap).

**Regra dos IDs:** um ID nunca é renumerado nem reutilizado. Um requisito removido continua na tabela,
marcado como *Removido*. Requisitos novos recebem o próximo número livre da sua seção, mesmo que isso
deixe a numeração fora de ordem.

## 1. Requisitos funcionais

### Identidade e acesso

| ID | Requisito | Fase |
|---|---|---|
| RF-01 | O usuário se autentica com credenciais e recebe um token JWT de curta duração. | MVP |
| RF-02 | Todo endpoint, exceto login, health e documentação da API, exige autenticação. | MVP |
| RF-03 | O usuário tem papéis (por exemplo, `OPERATOR` e `APPROVER`) que determinam o que ele pode fazer. | MVP (simples) |
| RF-04 | Organizações, convites de usuários e API keys para integrações máquina-a-máquina. | V5 |

### Ambientes e recursos

| ID | Requisito | Fase |
|---|---|---|
| RF-10 | Cadastrar, listar, consultar e desativar ambientes (nome, tipo `DOCKER`, classificação `DEV`/`STAGING`/`PROD`, referência de conexão). | MVP |
| RF-11 | Registrar os **serviços** de um ambiente, ou seja, a *allowlist* de containers que o agente pode ver e sobre os quais pode agir. Container fora da lista é invisível para o agente. | MVP |
| RF-12 | Testar a conectividade de um ambiente (`POST /environments/{id}/connectivity-check`). | MVP |
| RF-13 | Credenciais de ambiente (tokens, chaves) nunca são armazenadas em texto puro nem retornadas pela API. | MVP (via variáveis de ambiente) → V5 (cofre de segredos) |

### Conversa e execução do agente

| ID | Requisito | Fase |
|---|---|---|
| RF-20 | Enviar uma mensagem em linguagem natural ao agente, dentro de uma conversa associada a um ambiente. | MVP |
| RF-21 | Cada mensagem gera uma **execução** (`AgentExecution`) com identificador próprio, estado e linha do tempo de passos. | MVP |
| RF-22 | A execução é assíncrona: a API responde `202 Accepted` com o `executionId`, e o cliente consulta o estado. | MVP (polling) → V1 (SSE) |
| RF-23 | Cancelar uma execução em andamento ou aguardando aprovação. | MVP |
| RF-24 | A resposta de uma execução contém, além do texto do agente, a lista **estruturada** de **todas as ações propostas** e o desfecho de cada uma (executada com sucesso, falhou, negada pela política, aguardando aprovação, rejeitada, expirada, cancelada, resultado desconhecido). A lista é uma projeção dos registros de `ToolExecution` do banco, que são a fonte da verdade, e nunca é extraída do texto do LLM. Ação *proposta* e ação *executada* são coisas distintas, e ambas ficam visíveis. | MVP |
| RF-25 | Manter o contexto da conversa (mensagens anteriores) e o contexto operacional (ambiente, serviços, ações recentes). | MVP (básico) → V4 (avançado) |
| RF-26 | **Limites de execução.** Toda execução do agente tem limite de tempo de processamento ativo, de iterações do LLM, de chamadas de ferramenta e de retentativas por ferramenta (valores em RNF-CONF). Ao atingir qualquer limite, a execução termina no estado `BUDGET_EXCEEDED`, preservando os resultados parciais e informando qual limite foi atingido. O tempo esperando aprovação não conta como processamento ativo. | MVP |
| RF-27 | **Idempotência.** Operações que possam produzir efeito duplicado (criar execução, decidir aprovação, executar ferramenta com efeito colateral e, na V2, consumir mensagens) têm mecanismo de idempotência que impede execução duplicada causada por retentativas do cliente, cliques repetidos, reconexões, concorrência ou reprocessamento de mensagens. Veja o mecanismo na arquitetura, seção 9. | MVP (API + aprovação + execução) → V2 (mensageria) |

### Ferramentas

| ID | Requisito | Fase |
|---|---|---|
| RF-30 | Listar o catálogo de ferramentas com nome, descrição, schema de parâmetros, nível de risco, permissão exigida, se exige aprovação e timeout. | MVP |
| RF-31 | Ferramentas Docker read-only: listar containers, obter status (inspect) e obter logs (limitados). | MVP |
| RF-32 | Ferramenta Docker de risco: reiniciar container. | MVP |
| RF-33 | Diagnóstico determinístico de problemas simples a partir do status: `exited` com código ≠ 0, `OOMKilled`, `restarting` em loop, healthcheck `unhealthy`, container parado há pouco tempo. | MVP |
| RF-34 | Consumo de CPU e memória por container (Docker stats). | V1 |
| RF-35 | `startContainer` e `stopContainer`. | V1 |
| RF-36 | Ferramentas Git (branch, commits recentes, status, diff resumido). | V2 |
| RF-37 | Ferramentas GitHub (issues, PRs, comentários, criação de issue). | V2 |
| RF-38 | Ferramentas de CI/CD (status de pipeline, status de deploy, disparo de pipeline, rollback). | V2 |
| RF-39 | Ferramentas de monitoramento via Prometheus (health, taxa de erro, latência). | V3 |

### Segurança, aprovação e auditoria

| ID | Requisito | Fase |
|---|---|---|
| RF-40 | Toda chamada de ferramenta proposta pelo LLM passa por uma decisão de política: `ALLOW`, `REQUIRE_APPROVAL` ou `DENY`. | MVP |
| RF-41 | Quando a decisão é `REQUIRE_APPROVAL`, o backend cria uma **solicitação de aprovação** com ferramenta, parâmetros exatos, risco, impacto esperado, justificativa do agente e prazo de expiração. A execução fica pausada. | MVP |
| RF-42 | Um usuário com permissão aprova ou rejeita (com comentário opcional). A aprovação executa **exatamente** a chamada aprovada, sem pedir nova decisão ao LLM. | MVP |
| RF-43 | Aprovações expiram (padrão: 15 min) e são de uso único. | MVP |
| RF-44 | **Auditoria de ferramentas.** Toda chamada de ferramenta proposta, seja ela permitida, negada, aprovada, rejeitada, bem-sucedida ou com falha, gera um registro de auditoria com: usuário solicitante, execução, ferramenta, parâmetros **sanitizados**, recurso-alvo, nível de risco, decisão da política e motivo, aprovador e horário da aprovação (quando houver), horário de início e fim, duração, resultado e status. | MVP |
| RF-45 | Consultar a auditoria por recurso, usuário, ferramenta, execução e período (por exemplo, "quem reiniciou o container X?"). | MVP |
| RF-46 | Ver a justificativa de uma ação: o pedido do usuário, as observações anteriores e o raciocínio declarado pelo agente que levaram à chamada. | MVP |
| RF-47 | Notificar aprovações pendentes (e-mail, Slack ou webhook). | V2+ |
| RF-48 | **Aprovação obrigatória de ações de risco.** Toda chamada de ferramenta classificada como `HIGH_RISK` ou `DESTRUCTIVE` permanece no estado `WAITING_APPROVAL` e **não é executada** até que um usuário com a permissão `APPROVE` a aprove explicitamente. Na ausência de decisão, ela expira (RF-43). A única exceção futura é uma pré-autorização explícita, criada e auditada por um humano autorizado, para ações `HIGH_RISK` no nível `AUTOMATED` (RF-72). Ações `DESTRUCTIVE` **sempre** exigem aprovação por ação. | MVP |
| RF-49 | **A saída de ferramentas não concede autoridade.** Nenhum conteúdo vindo de sistemas externos (logs, respostas de API, texto de issues, variáveis de ambiente de containers) pode conceder, alterar ou elevar permissões, nível de autonomia, allowlist ou orçamento. Esses controles só mudam via API autenticada, por usuário autorizado, e cada mudança é auditada. | MVP |

### Incidentes e deploys

| ID | Requisito | Fase |
|---|---|---|
| RF-50 | Criar, listar e atualizar incidentes, vinculando execuções e achados. | V2 |
| RF-51 | Registrar e consultar deploys (versão, ambiente, status, autor, origem). | V2 |
| RF-52 | Criar uma issue no GitHub a partir de um incidente ou execução. | V2 |

### Plataforma (SaaS)

| ID | Requisito | Fase |
|---|---|---|
| RF-60 | Isolamento total entre organizações (tenants). | V5 |
| RF-61 | Cotas e limites de uso por organização (execuções por dia, tokens, custo). | V5 |
| RF-62 | Políticas de ferramentas configuráveis por organização e por ambiente. | V5 |
| RF-63 | Dashboard web. | V6 |

### Autonomia do agente

| ID | Requisito | Fase |
|---|---|---|
| RF-70 | Cada ambiente tem um **nível de autonomia** configurado por um usuário autorizado: `OBSERVE_ONLY`, `ASSISTED` ou `AUTOMATED`. Esse nível é uma entrada do Policy Engine, e o LLM não consegue lê-lo como instrução nem alterá-lo. | MVP (`OBSERVE_ONLY` e `ASSISTED`) |
| RF-71 | No nível `OBSERVE_ONLY`, apenas ferramentas `READ_ONLY` são oferecidas ao LLM e permitidas pela política. Qualquer outra proposta é negada. | MVP |
| RF-72 | No nível `AUTOMATED`, uma ação `HIGH_RISK` pode ser executada sem aprovação por ação **somente** se casar com uma regra de pré-autorização explícita (ferramenta + recurso + condição, por exemplo "restart do `demo-api` se o healthcheck estiver `unhealthy`"), criada por um humano com permissão e auditada. Ações `DESTRUCTIVE` nunca são automáticas. | Pós-MVP (V4+) |
| RF-73 | Execuções disparadas por eventos (um alerta, por exemplo) em vez de por um usuário, com um ator de sistema identificado na auditoria. | Pós-MVP (depende do RF-72) |

## 2. Requisitos não funcionais

### Segurança (prioridade máxima)

| ID | Requisito |
|---|---|
| RNF-SEG-01 | **Não existe execução de comandos arbitrários.** Apenas ferramentas registradas em código podem ser executadas. Nenhuma ferramenta aceita um comando ou script como parâmetro. |
| RNF-SEG-02 | Toda chamada proposta pelo LLM é validada em código: a ferramenta existe e está habilitada para o usuário e o ambiente, os parâmetros passam no schema, o recurso está na allowlist do ambiente, o usuário tem a permissão e o orçamento da execução não foi excedido. |
| RNF-SEG-03 | Menor privilégio no Docker: o backend não monta o socket. O acesso passa por um proxy que libera somente os endpoints usados. |
| RNF-SEG-04 | Nenhum segredo no código ou no repositório. Tudo vem por variáveis de ambiente ou arquivos de segredo, e o `.env` não é versionado (versiona-se só um `.env.example`). |
| RNF-SEG-05 | A saída das ferramentas (logs, por exemplo) é tratada como **dado não confiável**. Instruções contidas nela não alteram permissões, porque a política é aplicada em código. |
| RNF-SEG-06 | A aprovação fica vinculada a (execução, ferramenta, hash dos parâmetros), expira, é de uso único e só pode ser dada por um usuário com a permissão `APPROVE`. |
| RNF-SEG-07a | **Segredos do próprio sistema nunca aparecem em logs, auditoria, respostas da API nem prompts do LLM.** Isso inclui senhas, JWTs, API keys, credenciais de ambiente e tokens do GitHub. É uma **garantia por construção**: esses valores nunca são parâmetros de ferramenta (o backend os injeta no adapter), usam um tipo próprio cuja representação textual é mascarada, e são excluídos da serialização. Há testes automatizados que verificam isso. |
| RNF-SEG-07b | **Segredos em conteúdo de terceiros** (por exemplo, uma aplicação que imprime a própria senha no log) passam por mascaramento **best-effort**, com padrões conhecidos, antes de serem enviados ao LLM e antes de serem persistidos. Isso **não** é uma garantia, porque não dá para reconhecer todo segredo possível, e essa limitação é documentada no README. |
| RNF-SEG-08 | Senhas com hash forte (BCrypt ou Argon2), JWT com expiração curta e chave de assinatura vinda de configuração. |
| RNF-SEG-09 | A auditoria é *append-only*: a API não permite editar nem apagar registros. |
| RNF-SEG-10 | Os adapters de runtime mapeiam **somente os campos do domínio**. Variáveis de ambiente, comando, mounts e labels arbitrárias nunca saem do adapter. (Origem: threat model TM-B5-03) |
| RNF-SEG-11 | As saídas de ferramentas têm sequências ANSI e caracteres de controle removidos e caracteres invisíveis de formatação neutralizados, antes de irem ao LLM, ao banco e à tela. (TM-B5-05) |
| RNF-SEG-12 | O docker-socket-proxy só é alcançável pelo backend (rede interna, sem porta publicada), e o CI verifica que operações proibidas retornam `403`. (TM-B6-02/03) |
| RNF-SEG-13 | O estado e os papéis do usuário são recarregados do banco a cada requisição e a cada decisão de política. O JWT prova apenas a identidade. (TM-B1-03, TM-B2-01) |
| RNF-SEG-14 | Um recurso de outra organização, ou sem acesso, responde `404`, não `403`. O filtro de organização faz parte da **própria consulta** (`WHERE id = ? AND organization_id = ?`), e nunca é uma verificação feita depois de carregar o objeto. (TM-B1-04) |
| RNF-SEG-15 | Os serviços de infraestrutura do Compose só publicam portas em `127.0.0.1`, e as credenciais padrão são sempre substituídas via `.env`. (TM-B8-05) |
| RNF-SEG-16 | Os logs técnicos e as métricas não contêm conteúdo de mensagens, argumentos completos, saídas de ferramentas nem labels de alta cardinalidade. (TM-X-01/02) |
| RNF-SEG-17 | O login tem limitação de tentativas e resposta idêntica para usuário inexistente e senha errada. (TM-B1-02) |

### Confiabilidade e limites do agente

| ID | Requisito | Valor padrão inicial (configurável) |
|---|---|---|
| RNF-CONF-01 | Máximo de chamadas de ferramenta por execução | 10 |
| RNF-CONF-02 | Máximo de iterações do LLM por execução | 8 |
| RNF-CONF-03 | Tempo máximo de processamento ativo por execução (não conta a espera por aprovação) | 5 min |
| RNF-CONF-04 | Timeout por ferramenta | Definido na ferramenta (documento 05). Padrão de 10 s para read-only. |
| RNF-CONF-05 | Tamanho máximo da saída de uma ferramenta enviada ao LLM | ~16 KB, com os logs truncados às últimas N linhas |
| RNF-CONF-06 | Retentativas automáticas só para ferramentas **idempotentes read-only** e erros transitórios | no máximo 3 retentativas por chamada, com backoff exponencial |
| RNF-CONF-07 | Ferramentas `HIGH_RISK` ou `DESTRUCTIVE` **nunca** têm retentativa automática | — |
| RNF-CONF-08 | Uma execução em `WAITING_APPROVAL` sobrevive a um restart do backend, porque o estado fica no banco | — |
| RNF-CONF-09 | Na inicialização, execuções que ficaram `RUNNING` após um crash são marcadas como `INTERRUPTED`, e chamadas de ferramenta sem resultado são marcadas como `OUTCOME_UNKNOWN` | — |
| RNF-CONF-10 | Uma falha do LLM (timeout, 429, 5xx) gera uma execução `FAILED` com mensagem clara, sem deixar estado inconsistente | — |

> Os valores acima são **pontos de partida razoáveis, não medidos**. Vamos ajustá-los com dados reais
> das métricas depois do MVP.

### Observabilidade

| ID | Requisito | Fase |
|---|---|---|
| RNF-OBS-01 | Logs estruturados em JSON com `executionId`, `userId`, `toolName` e `traceId` no MDC. | MVP |
| RNF-OBS-02 | Métricas: duração e resultado por ferramenta, execuções por estado, chamadas ao LLM (latência, tokens, erros), custo estimado, aprovações (pedidas, aprovadas, rejeitadas, expiradas), negações de política e achados de diagnóstico. | MVP |
| RNF-OBS-03 | Prometheus + Grafana com um dashboard provisionado automaticamente. | MVP |
| RNF-OBS-04 | Tracing distribuído (OpenTelemetry), útil quando entrarem o broker e os workers. | V3 |

### Manutenibilidade e testabilidade

| ID | Requisito |
|---|---|
| RNF-MAN-01 | Monólito modular com fronteiras entre módulos verificadas por teste automatizado. |
| RNF-MAN-02 | Adicionar uma ferramenta nova exige apenas uma classe nova (com testes), **sem alterar o orquestrador**. |
| RNF-MAN-03 | O LLM fica atrás de uma interface (*port*). Os testes usam um LLM falso e determinístico, roteirizado. |
| RNF-MAN-04 | Migrações de banco versionadas (Flyway). O schema nunca é gerado automaticamente pelo Hibernate. |
| RNF-MAN-05 | A API é documentada com OpenAPI, gerada a partir do código. |

### Operação e portabilidade

| ID | Requisito |
|---|---|
| RNF-OPS-01 | Todo o ambiente de desenvolvimento sobe com `docker compose up`. |
| RNF-OPS-02 | Configuração 12-factor: tudo o que varia por ambiente vem de variáveis de ambiente. |
| RNF-OPS-03 | A imagem Docker do backend é multi-stage, roda como usuário não-root e tem healthcheck. |

### Custo

| ID | Requisito |
|---|---|
| RNF-CUS-01 | Cada chamada ao LLM registra os tokens de entrada e saída e o custo estimado (a tabela de preços fica em configuração, porque muda com frequência). |
| RNF-CUS-02 | Existe um orçamento diário configurável: ao estourar, novas execuções são recusadas com um erro claro. |

### Desempenho (metas, não garantias)

| ID | Requisito |
|---|---|
| RNF-DES-01 | Endpoints que não envolvem o agente: p95 < 200 ms em ambiente local (meta a ser medida). |
| RNF-DES-02 | O tempo de uma execução é dominado pela latência do LLM, e o design assume execuções de segundos a dezenas de segundos. Por isso a execução é assíncrona. |
