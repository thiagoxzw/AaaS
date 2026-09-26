# ADR-0001 — Monólito modular em vez de microsserviços

- **Status:** Aceita
- **Data:** 2026-09-26

## Contexto
Há um único desenvolvedor e um domínio coeso (agente, ferramentas, aprovações e auditoria mudam juntos).
O projeto precisa ser fácil de rodar localmente e de manter.

## Decisão
Uma única aplicação Spring Boot, dividida em módulos (`identity`, `environment`, `conversation`, `agent`,
`llm`, `tool`, `audit`, `shared`), com as regras de dependência entre eles verificadas por testes de
arquitetura.

## Alternativas consideradas
- **Microsserviços** (agent-service, tool-service, audit-service…): trariam comunicação de rede,
  consistência eventual, deploys múltiplos e tracing distribuído obrigatório, sem nenhum benefício de
  escala ou de autonomia de equipe nesta fase.
- **Monólito sem módulos**: é simples no início, mas as fronteiras se degradam e extrair um worker
  depois fica caro.

## Consequências
- (+) Deploy, debug e testes simples. Transações locais.
- (+) Extração futura viável: o módulo `agent` pode virar um worker consumindo do RabbitMQ.
- (−) Tudo escala junto. Isso é aceitável até haver métricas mostrando o contrário.
