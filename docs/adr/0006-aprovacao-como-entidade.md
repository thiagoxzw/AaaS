# ADR-0006 — Aprovação humana como entidade e máquina de estados, não como texto

- **Status:** Aceita
- **Data:** 2026-09-26

## Contexto
Um fluxo em que o LLM pergunta "Deseja continuar?" e o usuário responde "sim" no chat é ambíguo (sim
para o quê? com quais parâmetros?) e inseguro, porque o próprio LLM, ou um texto injetado, poderia
"interpretar" uma aprovação.

## Decisão
- A aprovação é uma entidade `Approval` com estados `PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED`.
- Ela fica vinculada a **execução + ferramenta + parâmetros exatos (com hash) + risco + justificativa +
  expiração**.
- A decisão só acontece por um endpoint dedicado (`POST /approvals/{id}/decision`), feito por um usuário
  autenticado com a permissão `APPROVE`.
- Ao aprovar, o backend executa **exatamente** a chamada registrada, sem consultar o LLM novamente para
  decidir.
- Um "sim" digitado no chat **não** aprova nada. No futuro, uma interface pode traduzir o clique em
  "Aprovar" para a chamada ao endpoint.

## Consequências
- (+) Dá para provar quem aprovou o quê, quando e com quais parâmetros.
- (+) Pode ser estendida para exigir um aprovador diferente do solicitante ("quatro olhos") na V5.
- (−) A experiência no MVP (via API) é menos fluida que um chat. Isso é resolvido na V6 com o dashboard.

## Implementação (fatia 7)
- A aprovação nasce na **mesma transação** da chamada em `WAITING_APPROVAL`, por um evento síncrono, sem o
  módulo `tool` depender de `approval`.
- A decisão acontece sob o *lock* da linha da aprovação e grava a auditoria na mesma transação. Só `PENDING`
  muda, então uma aprovação é usada uma vez.
- Ao aprovar, o backend executa a chamada gravada: compara o hash em três pontos (cópia da aprovação, hash da
  chamada e argumentos gravados vinculados de novo) e **reavalia a política** com o estado atual. Se ela negar,
  a aprovação continua `APPROVED` e a chamada vira `DENIED`.
- Rejeição, expiração e negação voltam ao loop; o modelo recebe só o status, nunca o comentário do aprovador.
- Detalhes, testes e divergências: [fatia 7](../fatias/07-aprovacao.md).
