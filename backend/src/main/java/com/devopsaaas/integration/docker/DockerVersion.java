package com.devopsaaas.integration.docker;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The part of {@code GET /version} a connectivity check reports. */
record DockerVersion(@JsonProperty("Version") String version, @JsonProperty("ApiVersion") String apiVersion) {
}
