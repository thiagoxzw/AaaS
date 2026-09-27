package com.devopsaaas.tool.diagnostics;

/** The deterministic container findings (docs/05-contratos-das-ferramentas.md, section 8.3). */
public enum ContainerFindingCode {
    CONTAINER_NOT_FOUND,
    OOM_KILLED,
    KILLED_BY_SIGKILL,
    EXITED_WITH_ERROR,
    STOPPED,
    UNHEALTHY,
    RESTART_LOOP,
    RECENTLY_STARTED,
    NO_HEALTHCHECK
}
