package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.common.error.*;
import com.mytallybook.accountbook.invite.store.InviteStore;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import com.mytallybook.accountbook.support.TestTransactions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class InviteTransactionServiceTests {
    static final Instant NOW=Instant.parse("2026-09-05T00:00:00Z");
    final AuthStore auth=mock(AuthStore.class);
    final MemberStore members=mock(MemberStore.class);
    final InviteStore invites=mock(InviteStore.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final MutableClock clock=new MutableClock();
    final LedgerWriteGuard guard=new LedgerWriteGuard(auth,members);
    final InviteTransactionService service=new InviteTransactionService(guard,auth,members,invites,audit,clock);
    final CurrentUser actor=new CurrentUser(1,1,11,MemberRole.OWNER);
    final WechatIdentity identity=new WechatIdentity("dummy-openid",null);
    final IssuedSessionToken session=new IssuedSessionToken("dummy-session","s".repeat(64),NOW.plusSeconds(3600));
    final MemberStore.MemberState owner=member(11,1,MemberRole.OWNER,"ACTIVE","ACTIVE");
    @BeforeEach void fixture() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true,10,1));
        when(members.lockLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1,1,10,"ACTIVE",1)));
        when(members.lockMembers()).thenReturn(List.of(owner));
        when(invites.lockByHash("hash")).thenReturn(Optional.of(row("ACTIVE",NOW.plusSeconds(60),1)));
        when(invites.lockById(51)).thenReturn(Optional.of(row("ACTIVE",NOW.plusSeconds(60),1)));
        when(auth.insertUser("dummy-openid",null,"微信用户",NOW)).thenReturn(2L);
        when(members.insertMember(2,NOW)).thenReturn(12L);
        when(invites.use(51,2,NOW)).thenReturn(1);
        when(invites.revoke(eq(51L),any())).thenReturn(1);
        when(invites.insert(anyString(),anyLong(),any(),any())).thenReturn(51L);
    }
    <T> T tx(Supplier<T> action) { return TestTransactions.template().execute(status->action.get()); }
    void fails(ErrorCode code,Supplier<?> action) {
        assertThatThrownBy(()->tx(action)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(code));
    }
    static MemberStore.MemberState member(long id,long userId,MemberRole role,String status,String userStatus) {
        return new MemberStore.MemberState(id,userId,userStatus,role,status,"retained nickname","retained alias",Instant.EPOCH);
    }
    static InviteStore.InviteRow row(String status,Instant expiry,long creator) {
        return new InviteStore.InviteRow(51,1,creator,NOW.minusSeconds(60),expiry,status,
                "USED".equals(status)?8L:null,"USED".equals(status)?NOW.minusSeconds(1):null);
    }
    @Test void acceptsNewUserAsMemberWithAtomicConsumptionAndAuditLast() {
        assertThat(tx(()->service.accept(identity,"hash",session,"req")).token()).isEqualTo("dummy-session");
        var order=inOrder(auth,members,invites,audit);
        order.verify(auth).lockAppConfig(); order.verify(members).lockLedger(); order.verify(members).lockMembers();
        order.verify(members).lockUserByOpenid("dummy-openid"); order.verify(invites).lockByHash("hash");
        order.verify(auth).insertUser("dummy-openid",null,"微信用户",NOW);
        order.verify(members).insertMember(2,NOW); order.verify(auth).revokeAllSessions(2,NOW);
        order.verify(auth).insertSession(2,"s".repeat(64),session.expiresAt(),NOW);
        order.verify(auth).updateLastLogin(2,NOW); order.verify(invites).use(51,2,NOW);
        var event=ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        order.verify(audit).append(event.capture());
        assertThat(event.getValue().details().keySet()).containsExactlyInAnyOrder("memberId","role","activeCount");
        assertThat(event.getValue().details().get("memberId")).isEqualTo(12L);
        assertThat(event.getValue().action()).isEqualTo("INVITE_ACCEPT");
    }
    @ParameterizedTest @ValueSource(strings={"LEFT","REMOVED"})
    void rejoinRetainsRelationshipAndNeverRestoresAdmin(String oldStatus) {
        when(members.lockUserByOpenid("dummy-openid")).thenReturn(Optional.of(new MemberStore.UserState(2,"ACTIVE")));
        when(members.lockMembers()).thenReturn(List.of(owner,member(12,2,MemberRole.ADMIN,oldStatus,"ACTIVE")));
        when(members.reactivateMember(12,2,oldStatus,NOW)).thenReturn(1);
        tx(()->service.accept(identity,"hash",session,"req"));
        verify(members).reactivateMember(12,2,oldStatus,NOW);
        verify(members,never()).insertMember(anyLong(),any());
        verify(auth,never()).insertUser(anyString(),any(),anyString(),any());
        verify(auth).revokeAllSessions(2,NOW);
        verify(auth,never()).updateUserProfile(anyLong(),anyBoolean(),any(),anyBoolean(),any(),any());
    }
    @Test void existingActiveUserWithoutRelationshipGetsNewMember() {
        when(members.lockUserByOpenid("dummy-openid")).thenReturn(Optional.of(new MemberStore.UserState(2,"ACTIVE")));
        tx(()->service.accept(identity,"hash",session,"req"));
        verify(auth,never()).insertUser(anyString(),any(),anyString(),any());
        verify(members).insertMember(2,NOW);
    }
    @Test void alreadyMemberDoesNotEvenLookupInviteOrChangeSessions() {
        when(members.lockUserByOpenid("dummy-openid")).thenReturn(Optional.of(new MemberStore.UserState(1,"ACTIVE")));
        fails(ErrorCode.ALREADY_MEMBER,()->service.accept(identity,"hash",session,"req"));
        verifyNoInteractions(invites,audit);
        noWrites();
    }
    @ParameterizedTest @CsvSource({"DISABLED,USER_DISABLED","DELETED,USER_UNAVAILABLE"})
    void unavailableUsersCannotBeReactivated(String status,ErrorCode code) {
        when(members.lockUserByOpenid("dummy-openid")).thenReturn(Optional.of(new MemberStore.UserState(2,status)));
        fails(code,()->service.accept(identity,"hash",session,"req"));
        verifyNoInteractions(invites,audit); noWrites();
    }
    @ParameterizedTest @CsvSource({"USED,INVITE_USED","REVOKED,INVITE_REVOKED","EXPIRED,INVITE_EXPIRED"})
    void storedTerminalStateWinsOverExpiryAndCreator(String state,ErrorCode code) {
        when(invites.lockByHash("hash")).thenReturn(Optional.of(row(state,NOW.minusSeconds(1),8)));
        fails(code,()->service.accept(identity,"hash",session,"req")); noWrites();
    }
    @Test void expiryAtNowWinsOverInvalidCreator() {
        when(invites.lockByHash("hash")).thenReturn(Optional.of(row("ACTIVE",NOW,8)));
        fails(ErrorCode.INVITE_EXPIRED,()->service.accept(identity,"hash",session,"req")); noWrites();
    }
    @Test void invalidCreatorMakesOtherwiseActiveInviteRevoked() {
        when(invites.lockByHash("hash")).thenReturn(Optional.of(row("ACTIVE",NOW.plusSeconds(60),8)));
        fails(ErrorCode.INVITE_REVOKED,()->service.accept(identity,"hash",session,"req")); noWrites();
    }
    @Test void waitingForInviteLockCannotUsePreLockTime() {
        when(invites.lockByHash("hash")).thenAnswer(call->{clock.value=NOW.plusSeconds(60);return Optional.of(row("ACTIVE",NOW.plusSeconds(60),1));});
        fails(ErrorCode.INVITE_EXPIRED,()->service.accept(identity,"hash",session,"req")); noWrites();
    }
    @Test void missingOrForeignInviteCannotWrite() {
        when(invites.lockByHash("hash")).thenReturn(Optional.empty());
        fails(ErrorCode.INVITE_INVALID,()->service.accept(identity,"hash",session,"req")); noWrites();
        when(invites.lockByHash("hash")).thenReturn(Optional.of(new InviteStore.InviteRow(51,2,1,NOW,NOW.plusSeconds(60),"ACTIVE",null,null)));
        fails(ErrorCode.INVITE_INVALID,()->service.accept(identity,"hash",session,"req")); noWrites();
    }
    @Test void lockedCapacityAppliesBeforeAnyInsertOrConsumption() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true,1,1));
        fails(ErrorCode.MEMBER_LIMIT_REACHED,()->service.accept(identity,"hash",session,"req")); noWrites();
    }
    @Test void disabledAndInactiveRelationshipsDoNotOccupyCapacity() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true,2,1));
        when(members.lockMembers()).thenReturn(List.of(owner,member(13,3,MemberRole.MEMBER,"ACTIVE","DISABLED"),
                member(14,4,MemberRole.MEMBER,"LEFT","ACTIVE")));
        tx(()->service.accept(identity,"hash",session,"req"));
        verify(invites).use(51,2,NOW);
    }
    @Test void expiredCandidateIsRejectedBeforeBusinessWrites() {
        fails(ErrorCode.CONFLICT,()->service.accept(identity,"hash",new IssuedSessionToken("x","h",NOW),"req")); noWrites();
    }
    @Test void candidateExpiringDuringMemberWritesCannotBeInserted() {
        when(members.insertMember(2,NOW)).thenAnswer(call->{clock.value=NOW.plusSeconds(3600);return 12L;});
        fails(ErrorCode.CONFLICT,()->service.accept(identity,"hash",session,"req"));
        verify(auth,never()).insertSession(anyLong(),anyString(),any(),any());
        verify(invites,never()).use(anyLong(),anyLong(),any()); verifyNoInteractions(audit);
    }
    @Test void conditionalConsumptionFailurePreventsSuccessAudit() {
        when(invites.use(51,2,NOW)).thenReturn(0);
        fails(ErrorCode.CONFLICT,()->service.accept(identity,"hash",session,"req")); verifyNoInteractions(audit);
    }
    @Test void auditFailurePropagatesToTransactionBoundary() {
        doThrow(new IllegalStateException("dummy-audit-failure")).when(audit).append(any());
        assertThatThrownBy(()->tx(()->service.accept(identity,"hash",session,"req")))
                .isInstanceOf(IllegalStateException.class).hasMessage("dummy-audit-failure");
    }
    @Test void creationUsesTimeAfterGuardAndOnlyPersistsHash() {
        when(auth.lockAppConfig()).thenAnswer(call->{clock.value=NOW.plusSeconds(30);return new AuthStore.AppConfigState(true,10,1);});
        var result=tx(()->service.create(actor,24,"raw-memory-only","hash-only","req"));
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(86430));
        verify(invites).insert("hash-only",1,NOW.plusSeconds(30),NOW.plusSeconds(86430));
        assertThat(result.token()).isEqualTo("raw-memory-only");
    }
    @Test void currentRoleNotStalePrincipalControlsWrites() {
        var otherOwner=member(13,3,MemberRole.OWNER,"ACTIVE","ACTIVE");
        when(members.lockLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1,3,10,"ACTIVE",2)));
        when(members.lockMembers()).thenReturn(List.of(otherOwner,member(11,1,MemberRole.MEMBER,"ACTIVE","ACTIVE")));
        fails(ErrorCode.ACCESS_DENIED,()->service.create(actor,24,"raw","hash","req"));
        fails(ErrorCode.ACCESS_DENIED,()->service.revoke(actor,51,"req"));
        verifyNoInteractions(invites,audit);
    }
    @Test void missingConfigOrOwnerInvariantFailsClosed() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(false,10,1));
        fails(ErrorCode.SYSTEM_NOT_INITIALIZED,()->service.accept(identity,"hash",session,"req"));
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true,10,1));
        when(members.lockMembers()).thenReturn(List.of());
        fails(ErrorCode.LEDGER_STATE_CONFLICT,()->service.create(actor,24,"raw","hash","req")); noWrites();
    }
    @Test void repeatedStoredRevokeDoesNotWriteOrAudit() {
        when(invites.lockById(51)).thenReturn(Optional.of(row("REVOKED",NOW.minusSeconds(1),1)));
        assertThat(tx(()->service.revoke(actor,51,"req")).status()).isEqualTo("REVOKED");
        verify(invites,never()).revoke(anyLong(),any()); verifyNoInteractions(audit);
    }
    @Test void explicitlyRevokesCreatorInvalidResidualRowOnce() {
        when(invites.lockById(51)).thenReturn(Optional.of(row("ACTIVE",NOW.plusSeconds(60),8)));
        assertThat(tx(()->service.revoke(actor,51,"req")).status()).isEqualTo("REVOKED");
        verify(invites).revoke(51,NOW); verify(audit).append(any());
    }
    @Test void usedExpiredAndMissingRevokeAreSafeFailures() {
        when(invites.lockById(51)).thenReturn(Optional.empty());
        fails(ErrorCode.RESOURCE_NOT_FOUND,()->service.revoke(actor,51,"req"));
        when(invites.lockById(51)).thenReturn(Optional.of(row("USED",NOW.minusSeconds(1),1)));
        fails(ErrorCode.INVITE_USED,()->service.revoke(actor,51,"req"));
        when(invites.lockById(51)).thenReturn(Optional.of(row("ACTIVE",NOW,1)));
        fails(ErrorCode.INVITE_EXPIRED,()->service.revoke(actor,51,"req"));
        verify(invites,never()).revoke(anyLong(),any()); verifyNoInteractions(audit);
    }
    @Test void sharedRevocationRequiresTransactionAndReadsTimeAfterInviteLocks() {
        assertThatThrownBy(()->service.revokeCreatedBy(1,actor,"ROLE_DEMOTION","req")).isInstanceOf(IllegalStateException.class);
        when(invites.lockCreatedBy(1)).thenAnswer(call->{
            clock.value=NOW.plusSeconds(60);
            return List.of(row("ACTIVE",NOW.plusSeconds(60),1),
                    new InviteStore.InviteRow(52,1,1,NOW,NOW.plusSeconds(61),"ACTIVE",null,null),
                    new InviteStore.InviteRow(53,1,1,NOW,NOW.plusSeconds(61),"USED",2L,NOW));
        });
        when(invites.revoke(52,NOW.plusSeconds(60))).thenReturn(1);
        assertThat(tx(()->{guard.lock();return service.revokeCreatedBy(1,actor,"ROLE_DEMOTION","req");})).containsExactly(52L);
        verify(invites,never()).revoke(eq(51L),any());
        var event=ArgumentCaptor.forClass(AuditLogService.AuditEvent.class); verify(audit).append(event.capture());
        assertThat(event.getValue().details().get("memberId")).isEqualTo(11L);
        assertThat(event.getValue().details().get("reason")).isEqualTo("ROLE_DEMOTION");
    }
    void noWrites() {
        verify(auth,never()).insertUser(anyString(),any(),anyString(),any());
        verify(members,never()).insertMember(anyLong(),any());
        verify(members,never()).reactivateMember(anyLong(),anyLong(),anyString(),any());
        verify(auth,never()).revokeAllSessions(anyLong(),any());
        verify(auth,never()).insertSession(anyLong(),anyString(),any(),any());
        verify(invites,never()).use(anyLong(),anyLong(),any()); verifyNoInteractions(audit);
    }
    static class MutableClock extends Clock {
        Instant value=NOW;
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return value;}
    }
}
