package com.devopsaaas.tool.container;

/** What a connectivity check learns about a runtime: its engine version and the API version it speaks. */
public record RuntimeVersion(String engineVersion, String apiVersion) {
}
