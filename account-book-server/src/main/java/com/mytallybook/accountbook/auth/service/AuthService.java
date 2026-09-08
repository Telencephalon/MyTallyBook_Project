package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.auth.store.AuthStore.LoginMembership;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public class AuthService {

    private final WechatSessionClient wechatSessionClient;
    private final AuthStore authStore;
    private final SessionTokenService sessionTokenService;
    private final AuthTransactionService transactionService;

    public AuthService(
            WechatSessionClient wechatSessionClient,
            AuthStore authStore,
            SessionTokenService sessionTokenService,
            AuthTransactionService transactionService
    ) {
        this.wechatSessionClient = wechatSessionClient;
        this.authStore = authStore;
        this.sessionTokenService = sessionTokenService;
        this.transactionService = transactionService;
    }

    public AuthResult login(String code, String requestId) {
        String normalizedCode = requireText(code, "code");
        WechatIdentity identity = wechatSessionClient.exchange(normalizedCode);
        AuthStore.AppConfigState config = authStore.readAppConfig();
        if (!config.initialized()) {
            return AuthResult.state(AuthState.NEED_BOOTSTRAP);
        }

        Optional<LoginMembership> membershipResult =
                authStore.findLoginMembership(identity.openid());
        if (membershipResult.isEmpty()) {
            return AuthResult.state(AuthState.INVITE_REQUIRED);
        }

        LoginMembership membership = membershipResult.get();
        if ("DISABLED".equals(membership.userStatus())) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        if (!isActiveMembership(membership)) {
            return AuthResult.state(AuthState.INVITE_REQUIRED);
        }

        IssuedSessionToken issued = sessionTokenService.issue();
        return transactionService.authenticate(membership.userId(), issued, requestId);
    }

    public void logout(String rawToken, CurrentUser currentUser, String requestId) {
        Objects.requireNonNull(currentUser, "currentUser must not be null");
        String tokenHash = sessionTokenService.hash(rawToken);
        transactionService.logout(tokenHash, currentUser, requestId);
    }

    private boolean isActiveMembership(LoginMembership membership) {
        return "ACTIVE".equals(membership.userStatus())
                && membership.ledgerId() != null
                && membership.memberId() != null
                && membership.role() != null
                && "ACTIVE".equals(membership.memberStatus())
                && "ACTIVE".equals(membership.ledgerStatus());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    ErrorCode.VALIDATION_FAILED.defaultMessage(),
                    java.util.Map.of(field, field + " must not be blank")
            );
        }
        return value.trim();
    }
}
