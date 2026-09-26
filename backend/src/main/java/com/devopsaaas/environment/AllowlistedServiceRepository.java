package com.devopsaaas.environment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface AllowlistedServiceRepository extends Repository<AllowlistedService, UUID> {

    <S extends AllowlistedService> S save(S service);

    Optional<AllowlistedService> findByIdAndEnvironmentIdAndOrganizationId(
            UUID id, UUID environmentId, UUID organizationId);

    List<AllowlistedService> findAllByEnvironmentIdAndOrganizationIdOrderByNameAsc(
            UUID environmentId, UUID organizationId);

    boolean existsByEnvironmentIdAndName(UUID environmentId, String name);

    boolean existsByEnvironmentIdAndContainerName(UUID environmentId, String containerName);
}
