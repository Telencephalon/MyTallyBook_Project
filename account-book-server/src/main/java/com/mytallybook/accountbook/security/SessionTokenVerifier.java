package com.mytallybook.accountbook.security;

import java.util.Optional;

@FunctionalInterface
public interface SessionTokenVerifier {

    Optional<CurrentUser> verify(String rawToken);
}
