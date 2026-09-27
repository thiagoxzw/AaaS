package com.devopsaaas.tool.builtin;

/** Input formats shared by the container tools. */
final class ServiceNames {

    /** The logical service name format of the allowlist (docs/04-modelo-de-dados.md, section 4.4). */
    static final String PATTERN = "^[a-z0-9][a-z0-9-]{0,62}$";

    static final String DESCRIPTION = "Logical service name from the environment's allowlist, as listed by "
            + "listContainers";

    private ServiceNames() {
    }
}
