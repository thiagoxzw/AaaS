# ADR-0002 — O backend controla o loop do agente; o LLM apenas propõe

- **Status:** Aceita
- **Data:** 2026-09-26

## Contexto
Vários frameworks de IA oferecem execução automática de ferramentas: o framework recebe o *tool call* do
modelo, executa a função e devolve o resultado ao modelo, tudo internamente. Isso é conveniente, mas tira
do nosso código o ponto exato em que precisamos checar permissão, pausar para aprovação, aplicar
orçamentos e auditar.

## Decisão
O **Agent Orchestrator** implementa o loop explicitamente:
1. envia ao LLM o contexto e o catálogo **filtrado** de ferramentas;
2. recebe uma **proposta** (chamar ferramentas ou responder);
3. cada proposta passa por Registry → validação de schema → Policy Engine → (Aprovação) → Executor;
4. o resultado volta ao LLM como observação;
5. os orçamentos (passos, iterações, tempo, tokens) são verificados a cada volta.

Se usarmos um framework de integração com LLM, a execução automática de ferramentas dele ficará
**desligada**. Antes de escolher o framework, vou confirmar na documentação oficial que ele permite isso.

## Alternativas consideradas
- **Deixar o framework executar as ferramentas**: menos código, mas a pausa para aprovação e a auditoria
  com a justificativa ficariam difíceis ou impossíveis de fazer de forma estruturada.
- **Fluxos 100% determinísticos (sem LLM)**: são seguros, mas não interpretam linguagem natural nem logs
  livres. Vamos usar regras determinísticas **onde elas bastam** (diagnóstico de status) e o LLM onde
  agrega valor (interpretação e explicação).

## Consequências
- (+) Segurança, auditoria e limites ficam em código testável.
- (+) O LLM é substituível por um fake roteirizado nos testes.
- (−) Mais código próprio para manter (o loop e a serialização do histórico de mensagens).
