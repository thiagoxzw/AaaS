package com.devopsaaas.identity;

import com.devopsaaas.shared.id.Ids;
import com.devopsaaas.shared.time.Timestamps;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    private UUID organizationId;
    private String email;
    private String displayName;

    /** Never serialized or logged (RNF-SEG-07a); {@link #toString()} does not include it. */
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private UserStatus status;

    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    // Roles are needed on every request to resolve permissions, and a user has at most four.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    private Set<RoleAssignment> roles = new HashSet<>();

    protected AppUser() {
    }

    static AppUser create(UUID organizationId, String email, String displayName, String passwordHash,
            Set<Role> roles) {
        AppUser user = new AppUser();
        user.id = Ids.newId();
        user.organizationId = organizationId;
        user.email = email;
        user.displayName = displayName;
        user.passwordHash = passwordHash;
        user.status = UserStatus.ACTIVE;
        user.createdAt = Timestamps.now();
        user.updatedAt = user.createdAt;
        roles.forEach(role -> user.roles.add(new RoleAssignment(organizationId, role)));
        return user;
    }

    void recordLogin() {
        lastLoginAt = Timestamps.now();
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public Set<Role> roles() {
        return roles.stream().map(RoleAssignment::getRole).collect(Collectors.toUnmodifiableSet());
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    @Override
    public String toString() {
        return "AppUser[id=" + id + ", organizationId=" + organizationId + ", status=" + status + "]";
    }
}
