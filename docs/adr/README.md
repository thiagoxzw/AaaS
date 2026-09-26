# Architecture Decision Records (ADRs)

Cada ADR registra **uma** decisão importante: o contexto, a decisão tomada, as alternativas
consideradas e as consequências. As ADRs não são apagadas. Quando uma decisão muda, cria-se uma nova ADR
que *substitui* a anterior.

| ADR | Título | Status |
|---|---|---|
| [0001](0001-monolito-modular.md) | Monólito modular em vez de microsserviços | Proposta |
| [0002](0002-backend-controla-o-loop.md) | O backend controla o loop do agente; o LLM apenas propõe | Proposta |
| [0003](0003-sem-shell-docker-via-proxy.md) | Sem shell; ferramentas tipadas; Docker via proxy com allowlist | Proposta |
| [0004](0004-adiar-redis-e-rabbitmq.md) | Adiar Redis e RabbitMQ até existir gatilho objetivo | Proposta |
| [0005](0005-organization-id-desde-o-mvp.md) | `organization_id` em todas as tabelas de domínio desde o MVP | Proposta |
| [0006](0006-aprovacao-como-entidade.md) | Aprovação humana como entidade e máquina de estados, não como texto | Proposta |
| [0007](0007-sem-memoria-vetorial-no-mvp.md) | Sem memória vetorial no MVP | Proposta |
