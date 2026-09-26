# ADR-0003 — Sem shell; ferramentas tipadas; Docker via proxy com allowlist

- **Status:** Proposta
- **Data:** 2026-09-26

## Contexto
O caminho mais curto para um "agente DevOps" é dar a ele um `exec(command)`. Isso permite qualquer ação,
inclusive destrutiva ou induzida por prompt injection (um log malicioso, por exemplo). Além disso, montar
`/var/run/docker.sock` num container dá a ele controle equivalente a root sobre o host.

## Decisão
1. Não existe nenhuma ferramenta que aceite comando, script ou expressão arbitrária.
2. Cada ferramenta é uma operação específica e tipada (`restartContainer(service)`), com schema de
   parâmetros validado.
3. Os parâmetros que apontam para recursos (containers) são resolvidos contra a **allowlist de serviços
   do ambiente**. O LLM passa um nome lógico, e o backend traduz para o container real.
4. O backend acessa o Docker por meio de um **proxy do socket** que libera somente os endpoints
   necessários (listar, inspecionar, ler logs e reiniciar containers). Vou confirmar a configuração exata
   do proxy escolhido na documentação dele durante a implementação.

## Alternativas consideradas
- **Montar o socket direto no backend**: mais simples, porém inseguro.
- **Docker via TCP com TLS mútuo**: seguro no transporte, mas não restringe *quais* operações são
  permitidas.
- **Agente remoto instalado no host-alvo**: é a direção certa para o SaaS (V5/V7), mas é complexo demais
  para o MVP.

## Consequências
- (+) A superfície de ataque é pequena e explícita. Prompt injection não amplia as permissões.
- (−) Toda capacidade nova exige implementar uma ferramenta nova, o que é **intencional**.
