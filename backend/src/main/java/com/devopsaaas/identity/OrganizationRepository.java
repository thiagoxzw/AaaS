package com.devopsaaas.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

interface OrganizationRepository extends Repository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);
}
