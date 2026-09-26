package com.devopsaaas.identity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Tenant root. Read-only in the MVP: the single organization is seeded by Flyway (V5). */
@Entity
@Table(name = "organization")
public class Organization {

    @Id
    private UUID id;

    @SuppressFBWarnings(value = "UWF_UNWRITTEN_FIELD", justification = "Populated by JPA from the seeded row")
    private String slug;

    protected Organization() {
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }
}
