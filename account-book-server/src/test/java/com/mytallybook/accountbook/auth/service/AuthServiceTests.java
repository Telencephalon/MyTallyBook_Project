package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

    private static final String REQUEST_ID = "request-auth-1";
    private static final WechatIdentity IDENTITY = new WechatIdentity("openid-1", "unionid-1");
    private static final IssuedSessionToken ISSUED = new IssuedSessionToken(
            "raw-token",
            "a".repeat(64),
            Instant.parse("2026-09-29T10:00:00Z")
    );

    @Mock
    private WechatSessionClient wechatSessionClient;

    @Mock
    private AuthStore authStore;

    @Mock
    private SessionTokenService sessionTokenService;

    @Mock
    private AuthTransactionService transactionService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                wechatSessionClient,
                authStore,
                sessionTokenService,
                transactionService
        );
    }

    @Test
    void returnsNeedBootstrapWithoutCreatingAUserOrSession() {
        stubWechatIdentity();
        when(authStore.readAppConfig())
                .thenReturn(new AuthStore.AppConfigState(false, 10, 0));

        AuthResult result = authService.login(" fresh-code ", REQUEST_ID);

        assertThat(result).isEqualTo(AuthResult.state(AuthState.NEED_BOOTSTRAP));
        verify(authStore, never()).findLoginMembership("openid-1");
        verify(sessionTokenService, never()).issue();
        verify(transactionService, never()).authenticate(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void returnsInviteRequiredForUnknownOrInactiveMembership() {
        stubWechatIdentity();
        when(authStore.readAppConfig())
                .thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(authStore.findLoginMembership("openid-1"))
                .thenReturn(Optional.empty());

        assertThat(authService.login("fresh-code", REQUEST_ID).state())
                .isEqualTo(AuthState.INVITE_REQUIRED);

        when(authStore.findLoginMembership("openid-1"))
                .thenReturn(Optional.of(membership("ACTIVE", "REMOVED", "ACTIVE")));
        assertThat(authService.login("fresh-code", REQUEST_ID).state())
                .isEqualTo(AuthState.INVITE_REQUIRED);
        verify(sessionTokenService, never()).issue();
    }

    @Test
    void rejectsDisabledUserWithStableBusinessError() {
        stubWechatIdentity();
        when(authStore.readAppConfig())
                .thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(authStore.findLoginMembership("openid-1"))
                .thenReturn(Optional.of(membership("DISABLED", "ACTIVE", "ACTIVE")));

        assertThatThrownBy(() -> authService.login("fresh-code", REQUEST_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.USER_DISABLED));
        verify(sessionTokenService, never()).issue();
    }

    @Test
    void exchangesCodeThenIssuesAndPersistsSessionForActiveMember() {
        stubWechatIdentity();
        AuthStore.LoginMembership membership = membership("ACTIVE", "ACTIVE", "ACTIVE");
        when(authStore.readAppConfig())
                .thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(authStore.findLoginMembership("openid-1"))
                .thenReturn(Optional.of(membership));
        when(sessionTokenService.issue()).thenReturn(ISSUED);
        when(transactionService.authenticate(membership.userId(), ISSUED, REQUEST_ID))
                .thenReturn(AuthResult.authenticated(ISSUED));

        AuthResult result = authService.login("fresh-code", REQUEST_ID);

        assertThat(result).isEqualTo(AuthResult.authenticated(ISSUED));
        InOrder order = inOrder(wechatSessionClient, authStore, sessionTokenService, transactionService);
        order.verify(wechatSessionClient).exchange("fresh-code");
        order.verify(authStore).readAppConfig();
        order.verify(authStore).findLoginMembership("openid-1");
        order.verify(sessionTokenService).issue();
        order.verify(transactionService).authenticate(membership.userId(), ISSUED, REQUEST_ID);
    }

    @Test
    void hashesCurrentTokenBeforeTransactionalLogout() {
        CurrentUser currentUser = new CurrentUser(7L, 1L, 11L, MemberRole.OWNER);
        when(sessionTokenService.hash("raw-token")).thenReturn("c".repeat(64));

        authService.logout("raw-token", currentUser, REQUEST_ID);

        InOrder order = inOrder(sessionTokenService, transactionService);
        order.verify(sessionTokenService).hash("raw-token");
        order.verify(transactionService)
                .logout("c".repeat(64), currentUser, REQUEST_ID);
    }

    private static AuthStore.LoginMembership membership(
            String userStatus,
            String memberStatus,
            String ledgerStatus
    ) {
        return new AuthStore.LoginMembership(
                7L,
                userStatus,
                1L,
                11L,
                MemberRole.OWNER,
                memberStatus,
                ledgerStatus
        );
    }

    private void stubWechatIdentity() {
        when(wechatSessionClient.exchange("fresh-code")).thenReturn(IDENTITY);
    }
}
