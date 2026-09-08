package com.mytallybook.accountbook.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.error.SafeExceptionLocation;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionTokenVerifier sessionTokenVerifier;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    public BearerTokenAuthenticationFilter(
            SessionTokenVerifier sessionTokenVerifier,
            RestAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.sessionTokenVerifier = sessionTokenVerifier;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!authorization.startsWith(BEARER_PREFIX)) {
            reject(request, response);
            return;
        }

        String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
        Optional<CurrentUser> currentUser;
        try {
            currentUser = rawToken.isEmpty()
                    ? Optional.empty()
                    : sessionTokenVerifier.verify(rawToken);
        } catch (RuntimeException exception) {
            log.error(
                    "Session token verification failed, requestId={}, method={}, route={}, exceptionType={}, location={}",
                    RequestIdFilter.getRequestId(request),
                    request.getMethod(),
                    "<pre-routing>",
                    exception.getClass().getName(),
                    SafeExceptionLocation.firstApplicationFrame(exception)
                );
            authenticationEntryPoint.writeError(request, response, ErrorCode.INTERNAL_ERROR);
            return;
        }
        if (currentUser.isEmpty()) {
            reject(request, response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new CurrentUserAuthenticationToken(currentUser.get())
        );
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        authenticationEntryPoint.commence(
                request,
                response,
                new BadCredentialsException("Invalid bearer token")
        );
    }
}
