# ADR-0006 — Aprovação humana como entidade e máquina de estados, não como texto

- **Status:** Proposta
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
