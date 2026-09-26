package com.devopsaaas.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** First administrator, read from ADMIN_EMAIL / ADMIN_PASSWORD. Only used while no user exists. */
@ConfigurationProperties("devops.bootstrap")
public record BootstrapProperties(String adminEmail, String adminPassword) {

    @Override
    public String toString() {
        return "BootstrapProperties[adminEmail=" + adminEmail + ", adminPassword=<redacted>]";
    }
}
