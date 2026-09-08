package com.mytallybook.accountbook.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

final class CurrentUserAuthenticationToken extends AbstractAuthenticationToken {

    private final CurrentUser currentUser;

    CurrentUserAuthenticationToken(CurrentUser currentUser) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + currentUser.role().name())));
        this.currentUser = currentUser;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public CurrentUser getPrincipal() {
        return currentUser;
    }
}
