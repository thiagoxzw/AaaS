# DevOps Agent as a Service (DevOps AaaS)

> **Um plano de controle seguro para agentes de operações. O LLM propõe ações, e o backend decide,
> executa, pede aprovação e audita.**

⚠️ **Status: fase de design.** Ainda não há código. Esta etapa define o problema, os requisitos e a
arquitetura de alto nível. O README completo (como executar, exemplos, screenshots, API) será escrito
conforme o sistema for construído.

## Documentação

| Documento | Conteúdo |
|---|---|
| [01 — Visão e problema](docs/01-visao-e-problema.md) | Validação da ideia, riscos, problema, personas, não-objetivos e critério de sucesso do MVP |
| [02 — Requisitos](docs/02-requisitos.md) | Requisitos funcionais e não funcionais, com IDs e fase |
| [03 — Arquitetura](docs/03-arquitetura.md) | Diagrama, componentes, fluxo do agente, máquina de estados, síncrono x assíncrono e stack |
| [ADRs](docs/adr/README.md) | Decisões arquiteturais registradas |

## Princípios

`clareza → segurança → simplicidade → manutenibilidade → escalabilidade`

- Nenhum acesso a shell e nenhum comando arbitrário: só ferramentas tipadas de um catálogo fechado.
- Permissões e aprovações aplicadas em código, nunca pelo LLM.
- Toda ação é auditada: quem, o quê, quando, por quê e com qual resultado.
- Regras determinísticas onde elas bastam, e LLM onde ele agrega valor.
- Tecnologias entram quando resolvem um problema real, não para parecer sofisticado.
