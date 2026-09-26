# ADR-0004 — Adiar Redis e RabbitMQ até existir gatilho objetivo

- **Status:** Aceita
- **Data:** 2026-09-26

## Contexto
Redis e RabbitMQ fazem parte da stack desejada, mas no MVP (um usuário, uma instância, execuções
guardadas no PostgreSQL) nenhum dos dois resolve um problema existente.

## Decisão
- A execução do agente é **assíncrona desde o MVP**, com um executor in-process de concorrência limitada.
  O despacho fica atrás de uma interface `ExecutionDispatcher`.
- **RabbitMQ entra na V2**, quando surgirem operações longas (deploy e pipelines), webhooks do GitHub,
  notificações com retry e DLQ, ou workers separados.
- **Redis entra na V4/V5**, quando houver múltiplas instâncias (locks distribuídos, fan-out de SSE) ou
  rate limiting por tenant ou API key.

## Alternativas consideradas
- **Incluir os dois desde o início**: mais peças para operar e testar sem ganho real, e é difícil
  justificá-los numa entrevista ("para que serve o Redis aqui?").
- **Não planejar a entrada deles**: tornaria a migração cara. A interface `ExecutionDispatcher` resolve
  isso.

## Consequências
- (+) O MVP fica menor, mais rápido de entregar e mais fácil de explicar.
- (−) Uma execução `RUNNING` interrompida por crash não é retomada automaticamente (é marcada
  `INTERRUPTED`). Isso é aceitável no MVP e está documentado.
