package com.mytallybook.accountbook.user;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserServiceTests {
    private final AuthStore store = mock(AuthStore.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final UserService service = new UserService(store, audit, Clock.systemUTC());

    @Test void removedActorCannotUpdateProfileUsingStaleAuthenticatedIdentity() {
        when(store.findUserProfile(7)).thenReturn(Optional.of(new AuthStore.UserProfileView(
                7, "old", null, 1, 11, MemberRole.MEMBER, null)));
        when(store.lockLoginMembership(7)).thenReturn(Optional.of(new AuthStore.LoginMembership(
                7, "ACTIVE", 1L, 11L, MemberRole.MEMBER, "REMOVED", "ACTIVE")));
        var request = new UpdateProfileRequest();
        request.setNickname("new");
        assertThatThrownBy(() -> service.update(new CurrentUser(7, 1, 11, MemberRole.MEMBER), request, "request"))
                .isInstanceOf(BusinessException.class);
        verify(store, never()).updateUserProfile(anyLong(), anyBoolean(), any(), anyBoolean(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test void validProfileWriteLocksConfigurationThenCurrentIdentityBeforeReadingOrWritingProfile() {
        when(store.lockLoginMembership(7)).thenReturn(Optional.of(new AuthStore.LoginMembership(
                7, "ACTIVE", 1L, 11L, MemberRole.MEMBER, "ACTIVE", "ACTIVE")));
        when(store.findUserProfile(7)).thenReturn(Optional.of(new AuthStore.UserProfileView(
                7, "old", null, 1, 11, MemberRole.MEMBER, null)), Optional.of(new AuthStore.UserProfileView(
                7, "new", null, 1, 11, MemberRole.MEMBER, null)));
        var request = new UpdateProfileRequest();
        request.setNickname("new");
        assertThat(service.update(new CurrentUser(7, 1, 11, MemberRole.ADMIN), request, "request").nickname()).isEqualTo("new");
        var order = inOrder(store, audit);
        order.verify(store).lockAppConfig();
        order.verify(store).lockLoginMembership(7);
        order.verify(store).findUserProfile(7);
        order.verify(store).updateUserProfile(eq(7L), eq(true), eq("new"), eq(false), isNull(), any());
        order.verify(audit).append(any());
    }

    @Test void mismatchedLedgerOrMemberCannotEditAnotherMembershipProfile() {
        when(store.lockLoginMembership(7)).thenReturn(Optional.of(new AuthStore.LoginMembership(
                7, "ACTIVE", 1L, 11L, MemberRole.MEMBER, "ACTIVE", "ACTIVE")));
        var request = new UpdateProfileRequest();
        request.setNickname("new");
        for (var actor : java.util.List.of(new CurrentUser(7, 2, 11, MemberRole.MEMBER), new CurrentUser(7, 1, 12, MemberRole.MEMBER))) {
            assertThatThrownBy(() -> service.update(actor, request, "request")).isInstanceOf(BusinessException.class);
        }
        verify(store, never()).updateUserProfile(anyLong(), anyBoolean(), any(), anyBoolean(), any(), any());
        verifyNoInteractions(audit);
    }
}
