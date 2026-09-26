package com.devopsaaas.environment;

import com.devopsaaas.audit.AuditAction;
import com.devopsaaas.audit.AuditEntry;
import com.devopsaaas.audit.AuditRecorder;
import com.devopsaaas.audit.AuditResourceType;
import com.devopsaaas.environment.EnvironmentCommands.EnvironmentChanges;
import com.devopsaaas.environment.EnvironmentCommands.NewEnvironment;
import com.devopsaaas.environment.EnvironmentCommands.NewService;
import com.devopsaaas.environment.EnvironmentCommands.ServiceChanges;
import com.devopsaaas.shared.error.ApiException;
import com.devopsaaas.shared.security.CurrentUser;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Environments and their allowlist. Every change is audited in the same transaction, and every lookup is
 * scoped to the caller's organization, so a resource of another tenant is simply "not found".
 */
@Service
public class EnvironmentManagement {

    private static final String ENVIRONMENT_NOT_FOUND = "Environment not found.";
    private static final String SERVICE_NOT_FOUND = "Service not found.";

    private final EnvironmentRepository environments;
    private final AllowlistedServiceRepository services;
    private final AuditRecorder audit;
    private final EntityManager entityManager;

    EnvironmentManagement(EnvironmentRepository environments, AllowlistedServiceRepository services,
            AuditRecorder audit, EntityManager entityManager) {
        this.environments = environments;
        this.services = services;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional
    public Environment create(CurrentUser user, NewEnvironment command) {
        rejectUnsupportedAutonomy(command.autonomyLevel());
        if (environments.existsByOrganizationIdAndName(user.organizationId(), command.name())) {
            throw ApiException.conflict("An environment with this name already exists.");
        }
        Environment environment = environments.save(Environment.create(user.organizationId(), user.userId(),
                command.name(), command.description(), command.type(), command.tier(), command.autonomyLevel(),
                command.connectionRef()));
        audit.record(AuditEntry.byUser(user, AuditAction.ENVIRONMENT_CREATED, AuditResourceType.ENVIRONMENT,
                        environment.getId())
                .detail("name", environment.getName())
                .detail("type", environment.getType())
                .detail("tier", environment.getTier())
                .detail("autonomyLevel", environment.getAutonomyLevel())
                .detail("connectionRef", environment.getConnectionRef()));
        entityManager.flush();
        return environment;
    }

    @Transactional(readOnly = true)
    public List<Environment> list(CurrentUser user) {
        return environments.findAllByOrganizationIdOrderByNameAsc(user.organizationId());
    }

    @Transactional(readOnly = true)
    public Environment get(CurrentUser user, UUID environmentId) {
        return environments.findByIdAndOrganizationId(environmentId, user.organizationId())
                .orElseThrow(() -> ApiException.notFound(ENVIRONMENT_NOT_FOUND));
    }

    @Transactional
    public Environment update(CurrentUser user, UUID environmentId, EnvironmentChanges changes) {
        Environment environment = get(user, environmentId);
        requireVersion(environment.getVersion(), changes.version());
        rejectUnsupportedAutonomy(changes.autonomyLevel());

        AuditEntry updated = AuditEntry.byUser(user, AuditAction.ENVIRONMENT_UPDATED,
                AuditResourceType.ENVIRONMENT, environment.getId());
        boolean anyChange = false;
        if (changes.description() != null && !changes.description().equals(environment.getDescription())) {
            updated.detail("description", changes.description());
            environment.changeDescription(changes.description());
            anyChange = true;
        }
        if (changes.tier() != null && changes.tier() != environment.getTier()) {
            updated.detail("tierFrom", environment.getTier()).detail("tierTo", changes.tier());
            environment.changeTier(changes.tier());
            anyChange = true;
        }
        if (changes.status() != null && changes.status() != environment.getStatus()) {
            updated.detail("statusFrom", environment.getStatus()).detail("statusTo", changes.status());
            environment.changeStatus(changes.status());
            anyChange = true;
        }
        if (anyChange) {
            audit.record(updated);
        }
        if (changes.autonomyLevel() != null && changes.autonomyLevel() != environment.getAutonomyLevel()) {
            // Recorded as its own event: it is the security-sensitive change of an environment (ADR-0008).
            audit.record(AuditEntry.byUser(user, AuditAction.ENVIRONMENT_AUTONOMY_CHANGED,
                            AuditResourceType.ENVIRONMENT, environment.getId())
                    .detail("from", environment.getAutonomyLevel())
                    .detail("to", changes.autonomyLevel()));
            environment.changeAutonomyLevel(changes.autonomyLevel());
        }
        entityManager.flush();
        return environment;
    }

    @Transactional
    public AllowlistedService addService(CurrentUser user, UUID environmentId, NewService command) {
        Environment environment = get(user, environmentId);
        if (services.existsByEnvironmentIdAndName(environment.getId(), command.name())) {
            throw ApiException.conflict("A service with this name already exists in the environment.");
        }
        if (services.existsByEnvironmentIdAndContainerName(environment.getId(), command.containerName())) {
            throw ApiException.conflict("This container is already allowlisted in the environment.");
        }
        AllowlistedService service = services.save(AllowlistedService.create(environment, command.name(),
                command.containerName(), command.description()));
        audit.record(AuditEntry.byUser(user, AuditAction.SERVICE_ALLOWLISTED, AuditResourceType.SERVICE,
                        service.getId())
                .detail("environmentId", environment.getId())
                .detail("name", service.getName())
                .detail("containerName", service.getContainerName()));
        entityManager.flush();
        return service;
    }

    @Transactional(readOnly = true)
    public List<AllowlistedService> listServices(CurrentUser user, UUID environmentId) {
        Environment environment = get(user, environmentId);
        return services.findAllByEnvironmentIdAndOrganizationIdOrderByNameAsc(environment.getId(),
                user.organizationId());
    }

    @Transactional
    public AllowlistedService updateService(CurrentUser user, UUID environmentId, UUID serviceId,
            ServiceChanges changes) {
        AllowlistedService service = services
                .findByIdAndEnvironmentIdAndOrganizationId(serviceId, environmentId, user.organizationId())
                .orElseThrow(() -> ApiException.notFound(SERVICE_NOT_FOUND));
        requireVersion(service.getVersion(), changes.version());

        if (changes.description() != null && !changes.description().equals(service.getDescription())) {
            service.changeDescription(changes.description());
            audit.record(AuditEntry.byUser(user, AuditAction.SERVICE_UPDATED, AuditResourceType.SERVICE,
                            service.getId())
                    .detail("environmentId", environmentId)
                    .detail("description", changes.description()));
        }
        if (changes.enabled() != null && changes.enabled() != service.isEnabled()) {
            service.changeEnabled(changes.enabled());
            AuditAction action = changes.enabled() ? AuditAction.SERVICE_ENABLED : AuditAction.SERVICE_DISABLED;
            audit.record(AuditEntry.byUser(user, action, AuditResourceType.SERVICE, service.getId())
                    .detail("environmentId", environmentId)
                    .detail("name", service.getName()));
        }
        entityManager.flush();
        return service;
    }

    private static void rejectUnsupportedAutonomy(AutonomyLevel autonomyLevel) {
        if (autonomyLevel == AutonomyLevel.AUTOMATED) {
            throw ApiException.unprocessable("Autonomy level AUTOMATED is not supported yet (see ADR-0008).");
        }
    }

    private static void requireVersion(Long current, long expected) {
        if (!Objects.equals(current, expected)) {
            throw ApiException.conflict("The resource was modified by someone else. Reload it and try again.");
        }
    }
}
