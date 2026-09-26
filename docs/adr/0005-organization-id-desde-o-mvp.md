# ADR-0005 — `organization_id` em todas as tabelas de domínio desde o MVP

- **Status:** Aceita
- **Data:** 2026-09-26

## Contexto
O multi-tenancy completo é da V5. Adicionar a coluna do tenant depois exige migrar todas as tabelas e
revisar todas as consultas, que é justamente onde nascem vazamentos entre tenants.

## Decisão
Todas as tabelas de domínio terão `organization_id NOT NULL` desde a primeira migração. O seed cria uma
única organização. A estratégia completa (V5) prevê schema compartilhado, filtro obrigatório na camada
de dados e **Row-Level Security do PostgreSQL** como defesa em profundidade.

## Alternativas consideradas
- **Banco ou schema por tenant**: isolamento mais forte, mas operação muito mais cara (migrações por
  tenant, pool de conexões). Pode ser uma opção "enterprise" no futuro.
- **Adiar a coluna**: economiza pouco agora e custa caro depois.

## Consequências
- (+) A V5 vira "ativar e testar" o isolamento, em vez de "reescrever".
- (−) Há uma coluna e um índice extra em cada tabela desde já. O custo é desprezível.
