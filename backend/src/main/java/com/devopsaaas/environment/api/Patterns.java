package com.devopsaaas.environment.api;

final class Patterns {

    /** Logical names the LLM will see (docs/04-modelo-de-dados.md, section 4.4). */
    static final String LOGICAL_NAME = "^[a-z0-9][a-z0-9-]{0,62}$";

    /** A logical connection name, so a URL (and any credential embedded in it) cannot be stored (RF-13). */
    static final String CONNECTION_REF = "^[a-z0-9][a-z0-9-]{0,63}$";

    /** Docker container name charset. */
    static final String CONTAINER_NAME = "^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$";

    private Patterns() {
    }
}
