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
