package com.devopsaaas.environment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare {@link Repository} on purpose: there is no inherited {@code findById} or {@code findAll},
 * so every lookup has to name the organization (RNF-SEG-14).
 */
interface EnvironmentRepository extends Repository<Environment, UUID> {

    <S extends Environment> S save(S environment);

    Optional<Environment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Environment> findAllByOrganizationIdOrderByNameAsc(UUID organizationId);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);
}
