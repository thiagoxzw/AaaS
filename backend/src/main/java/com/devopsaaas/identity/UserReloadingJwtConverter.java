package com.devopsaaas.identity;

import com.devopsaaas.shared.security.CurrentUser;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a valid JWT into an authentication by reloading the user from the database. A disabled or deleted
 * user is rejected immediately, and role changes apply on the next request, even while the token is still
 * valid (TM-B1-03, RNF-SEG-13).
 */
@Component
class UserReloadingJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppUserRepository users;
    private final PermissionResolver permissions;

    UserReloadingJwtConverter(AppUserRepository users, PermissionResolver permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AppUser user = users.findForAuthenticationById(parseSubject(jwt.getSubject()))
                .filter(AppUser::isActive)
                .orElseThrow(() -> new BadCredentialsException("Unknown or disabled user"));
        return new CurrentUserAuthentication(new CurrentUser(user.getId(), user.getOrganizationId(),
                user.getEmail(), permissions.permissionsFor(user.roles())));
    }

    private static UUID parseSubject(String subject) {
        if (subject == null) {
            throw new BadCredentialsException("Missing token subject");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException("Invalid token subject", exception);
        }
    }
}
