# ADR-0009 — `AgentExecution` separado de `ToolExecution`; o histórico é a fonte da verdade

- **Status:** Aceita
- **Data:** 2026-09-26

## Contexto
Uma execução do agente envolve várias chamadas de ferramenta, algumas negadas, algumas aguardando
aprovação e outras executadas. A resposta ao usuário, a auditoria e o futuro dashboard precisam mostrar
**o que realmente aconteceu**, e não o que o LLM diz que aconteceu.

## Decisão
- `AgentExecution` (agregado raiz) 1 → N `ToolExecution`, e cada `ToolExecution` 1 → 0..1 `Approval`.
- **Toda proposta do LLM vira uma `ToolExecution`**, inclusive as negadas pela política, com o status e o
  motivo registrados.
- A lista de ações da resposta da API é uma **projeção** desses registros. Não existe uma lista separada
  de "ações planejadas" que possa divergir.
- O status da execução da ferramenta e a decisão de aprovação são **campos independentes**.
- A `ToolExecution` com efeito colateral é gravada como `RUNNING` **antes** da chamada externa. Se o
  processo cair no meio, o registro fica como evidência (`OUTCOME_UNKNOWN`).

## Alternativas consideradas
- **Guardar as ações só como texto ou JSON na mensagem do agente**: difícil de consultar ("quem
  reiniciou X?") e confia no LLM.
- **Status combinado** (`APPROVED_AND_EXECUTED`): gera uma explosão de combinações.
- **Listas separadas `plannedActions` e `executedActions`**: duas fontes que podem divergir.

## Consequências
- (+) A auditoria, a resposta e o dashboard leem a mesma fonte.
- (+) Tentativas negadas (inclusive as induzidas por prompt injection) ficam visíveis e mensuráveis.
- (−) Mais linhas no banco por execução. O volume é irrelevante nesta escala.
