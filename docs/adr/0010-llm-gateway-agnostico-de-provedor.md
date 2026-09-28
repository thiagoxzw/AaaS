# ADR-0010 — LLM Gateway agnóstico de provedor; OpenAI como primeira implementação

- **Status:** Aceita
- **Data:** 2026-09-26

## Contexto
O agente precisa de um LLM com suporte confiável a tool calling. Provedores, modelos e preços mudam com
frequência. Se o orchestrator chamar diretamente algo como `OpenAIService.doSomething()`, a troca de
provedor, os testes e o raciocínio sobre segurança ficam acoplados a um fornecedor.

## Decisão
- O módulo `llm` expõe uma interface própria, `LlmGateway`, com **tipos neutros de provedor**:
  - a requisição contém as mensagens, as definições de ferramentas (nome, descrição, JSON Schema dos
    parâmetros) e os limites (máximo de tokens, timeout);
  - a resposta contém o texto e/ou as **propostas** de chamada de ferramenta (id, nome, argumentos brutos),
    o motivo de término e o uso de tokens.
- `Agent → LlmGateway → Provider`. O orchestrator não conhece o provedor.
- A primeira (e, no MVP, única) implementação é o `OpenAiLlmAdapter`.
- Um único modelo, definido em configuração (`.env`). **Não** haverá roteamento entre vários modelos.
- A tabela de preços usada para estimar custo fica em configuração, porque os preços mudam.
- O adapter **não executa ferramentas**. Ele só traduz formatos (ADR-002).
- Os testes usam um `ScriptedLlmGateway` (roteirizado e determinístico), incluindo cenários maliciosos.

## Alternativas consideradas
- **Usar diretamente as abstrações de um framework** (Spring AI, por exemplo) no orchestrator: reduz
  código, mas acopla o núcleo ao modelo de execução do framework. Continua possível usar o framework
  **dentro** do adapter, se ele permitir desligar a execução automática de ferramentas. Isso será
  decidido na implementação, depois de verificar a documentação atual.
- **Suportar vários provedores desde o início**: complexidade sem necessidade.
- **Modelo local**: zero custo por token, mas a qualidade do tool calling é incerta. Continua possível
  como um segundo adapter no futuro.

## Consequências
- (+) Trocar ou adicionar um provedor é implementar um adapter. O orchestrator e os testes não mudam.
- (+) Os testes do agente não dependem de rede, custo ou não-determinismo.
- (−) É preciso manter o mapeamento entre os tipos neutros e o formato do provedor.
- (−) O conteúdo enviado ao LLM (incluindo logs mascarados) sai da máquina local para a OpenAI. Isso é
  documentado no README.

## Decisão de implementação (fatia 6, 2026-09-27)

- **Cliente HTTP próprio** (`HttpClient` do JDK), no pacote `integration.openai`, e não o SDK oficial nem o
  Spring AI. O SDK `com.openai:openai-java-core` 4.69.3 traz Jackson 2 e Kotlin para um projeto em Jackson 3; o
  Spring AI acrescentaria um segundo modelo de mensagens. O cliente próprio deixa visível e testável cada byte
  enviado ao provedor (TM-B3-01).
- **Responses API (`POST /responses`) sem estado:** `store: false`, e o histórico inteiro vai em cada chamada,
  reconstruído dos nossos registros. Sem `previous_response_id`: o banco continua sendo a fonte da verdade.
- **`strict: false`** nas ferramentas: a validação estrita dos argumentos é do backend (`ArgumentBinder`).
- **Até 2 retentativas** em 429 e 5xx, respeitando `Retry-After`, dentro do timeout da chamada; 4xx sem
  retentativa (`REJECTED`). Depois da validação com o modelo real, um 429 de conta sem crédito
  (`error.type = insufficient_quota`) também não é retentado (`QUOTA_EXHAUSTED`): é o único campo do corpo de
  erro que o adapter lê.
- **Modelo, preços e orçamento diário sem valor padrão:** faltando qualquer um, a aplicação não sobe.
- **Ressalva:** a documentação oficial da OpenAI não era alcançável do ambiente de desenvolvimento. O formato
  foi lido em fontes de terceiros, fixado pelos testes com stub e **precisa ser confirmado na primeira execução
  real** (roteiro em [docs/fatias/06-llm-real.md](../fatias/06-llm-real.md)).

