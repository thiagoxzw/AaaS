package com.devopsaaas.tool.execution;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface ToolExecutionRepository extends Repository<ToolExecution, UUID> {

    <S extends ToolExecution> S save(S execution);

    Optional<ToolExecution> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
