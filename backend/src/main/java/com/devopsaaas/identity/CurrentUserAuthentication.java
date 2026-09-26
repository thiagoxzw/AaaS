package com.devopsaaas.identity;

import com.devopsaaas.shared.security.CurrentUser;
import java.io.Serial;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication whose principal is the freshly loaded {@link CurrentUser}; authorities are permissions. */
final class CurrentUserAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final CurrentUser user;

    CurrentUserAuthentication(CurrentUser user) {
        super(user.permissions().stream().map(permission -> new SimpleGrantedAuthority(permission.name())).toList());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public CurrentUser getPrincipal() {
        return user;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return user.userId().toString();
    }

    @Override
    public boolean equals(Object other) {
        return super.equals(other);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
