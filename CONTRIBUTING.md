# Convenções do projeto

## Idioma

| Artefato | Idioma |
|---|---|
| README, `docs/`, ADRs, requisitos, issues | Português |
| Classes, métodos, variáveis, enums, pacotes | Inglês |
| Endpoints e campos JSON da API | Inglês |
| Tabelas e colunas do banco | Inglês |
| Logs técnicos e mensagens de exceção | Inglês |
| Nomes de testes | Inglês |
| Mensagens de commit | Inglês |

```java
// sim
public class AgentExecution {
    private ExecutionStatus status;
    private Instant startedAt;
}

// não
public class ExecucaoAgente {
    private StatusExecucao status;
}
```

## Commits

Seguem o padrão [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add agent execution lifecycle
feat: add Docker tool registry
fix: prevent unauthorized tool execution
test: add approval policy tests
docs: add data model
refactor: extract policy decision chain
chore: bump dependencies
ci: add Docker image build
```

- Modo imperativo, em minúsculas, sem ponto final.
- Um commit por mudança coerente. A mensagem explica o **porquê** quando ele não é óbvio.

## Banco de dados

- Tabelas e colunas em `snake_case`, com nomes de tabela no singular (`agent_execution`, `tool_execution`).
- O schema só muda por migração Flyway versionada. Nunca por `ddl-auto`.

## Requisitos e decisões

- Um requisito é referenciado pelo ID (`RF-48`, `RNF-SEG-07a`) em testes, issues e PRs.
- Toda decisão arquitetural relevante vira uma ADR em `docs/adr/`.
