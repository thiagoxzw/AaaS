package com.devopsaaas.integration.docker;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The part of {@code GET /containers/{id}/json} the domain needs, and nothing more (RNF-SEG-10). The
 * response also carries {@code Config.Env}, {@code Config.Cmd}, {@code Mounts} and the like; they are not
 * declared here, so the parser skips them and they never become objects in the backend.
 */
record DockerInspect(
        @JsonProperty("RestartCount") int restartCount,
        @JsonProperty("State") State state,
        @JsonProperty("Config") Config config) {

    record State(
            @JsonProperty("Status") String status,
            @JsonProperty("ExitCode") Integer exitCode,
            @JsonProperty("OOMKilled") boolean oomKilled,
            @JsonProperty("StartedAt") String startedAt,
            @JsonProperty("FinishedAt") String finishedAt,
            @JsonProperty("Health") Health health) {
    }

    record Health(@JsonProperty("Status") String status) {
    }

    /** Only the image name. Env, Cmd, Entrypoint, Labels: deliberately absent. */
    record Config(@JsonProperty("Image") String image) {
    }
}
