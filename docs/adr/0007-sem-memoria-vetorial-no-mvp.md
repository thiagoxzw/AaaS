# ADR-0007 — Sem memória vetorial no MVP

- **Status:** Proposta
- **Data:** 2026-09-26

## Contexto
É comum adicionar um banco vetorial a qualquer aplicação de IA. Aqui, porém, o contexto necessário
(ambiente, serviços, últimas ações, deploys, incidentes) é **estruturado** e se obtém com consultas SQL
exatas, o que é mais barato, previsível e auditável que uma busca por similaridade.

## Decisão
- O MVP não usa embeddings nem banco vetorial.
- O conhecimento não estruturado (runbooks, postmortems) entra na V4 com **busca full-text do
  PostgreSQL**.
- Vetores só entram (via `pgvector`, sem um banco novo) se houver evidência de que a busca textual não
  encontra o conteúdo relevante: por exemplo, consultas em linguagem natural que não compartilham
  palavras com o runbook certo.

## Consequências
- (+) Menos infraestrutura, custo e comportamento não determinístico.
- (−) A busca semântica em documentação fica para depois, e só se for justificada.
