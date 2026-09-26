# ADR-0008 — Níveis de autonomia como configuração de política

- **Status:** Proposta
- **Data:** 2026-09-26

## Contexto
Quanto o agente pode fazer sem um humano no meio precisa ser uma decisão **explícita, configurável e
auditável**. Não pode depender do que o LLM "acha" apropriado nem de instruções no prompt.

## Decisão
- Cada ambiente tem um nível de autonomia: `OBSERVE_ONLY`, `ASSISTED` ou `AUTOMATED`.
- O nível é uma **entrada do Policy Engine**. Ele filtra quais ferramentas são oferecidas ao LLM e decide
  `ALLOW`, `REQUIRE_APPROVAL` ou `DENY` pela matriz risco × autonomia (arquitetura, seção 5.3).
- O MVP suporta `OBSERVE_ONLY` e `ASSISTED`. O `AUTOMATED` vem depois do MVP e só permite ações
  `HIGH_RISK` que casem com uma **regra de pré-autorização** criada e auditada por um humano. Ações
  `DESTRUCTIVE` nunca são automáticas.
- Mudar o nível exige permissão administrativa, e a mudança é auditada. Nenhum conteúdo externo altera o
  nível (RF-49).
- O nome `OBSERVE_ONLY` foi escolhido para não colidir com o nível de **risco** `READ_ONLY`.

## Alternativas consideradas
- **Aprovação sempre obrigatória para qualquer escrita**: é seguro, mas impede a evolução para automação
  controlada.
- **O LLM decide quando pedir aprovação**: inaceitável, porque a autoridade estaria no modelo.
- **Autonomia por usuário, em vez de por ambiente**: o risco depende principalmente de **onde** a ação
  acontece (DEV ou PROD). A permissão do usuário continua valendo como interseção (arquitetura, seção
  2.2). A autonomia por agente pode ser adicionada na V5.

## Consequências
- (+) A mesma base de código atende de "só observa" até "automação controlada", mudando configuração.
- (+) Resposta clara em entrevista: "o nível de autonomia é uma configuração de segurança, não uma
  decisão do LLM."
- (−) O `AUTOMATED` exige um modelo de regras de pré-autorização, que ainda precisa ser desenhado.
