package com.devopsaaas.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface AppUserRepository extends Repository<AppUser, UUID> {

    <S extends AppUser> S save(S user);

    long count();

    // The only two lookups not scoped by organization, on purpose: authentication happens before the
    // tenant is known, and e-mails are globally unique in the MVP (docs/fatias/01-autenticacao-ambientes-auditoria.md).

    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findForAuthenticationByEmail(@Param("email") String email);

    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findForAuthenticationById(@Param("id") UUID id);
}
