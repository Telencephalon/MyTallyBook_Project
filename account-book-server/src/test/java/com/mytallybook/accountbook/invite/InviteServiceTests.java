package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.config.AuthProperties;
import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.auth.wechat.*;
import com.mytallybook.accountbook.common.error.*;
import com.mytallybook.accountbook.invite.store.InviteStore;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import com.mytallybook.accountbook.support.TestTransactions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.security.SecureRandom;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class InviteServiceTests {
    final AuthStore auth=mock(AuthStore.class);
    final MemberStore members=mock(MemberStore.class);
    final InviteStore invites=mock(InviteStore.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final WechatSessionClient wechat=mock(WechatSessionClient.class);
    final Instant now=Instant.parse("2026-09-05T00:00:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final CurrentUser actor=new CurrentUser(1,1,11,MemberRole.OWNER);
    final InviteService service;
    InviteServiceTests() {
        var props=new AuthProperties("","dummy-pepper-0123456789abcdef0123456789",Duration.ofDays(7),32);
        var target=new InviteTransactionService(new LedgerWriteGuard(auth,members),auth,members,invites,audit,clock);
        var proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(TestTransactions.template().getTransactionManager(),new AnnotationTransactionAttributeSource()));
        service=new InviteService(new InviteTokenService(props,new SecureRandom()),
                new SessionTokenService(props,new SecureRandom(),clock),wechat,(InviteTransactionService)proxy.getProxy());
    }
    @BeforeEach void fixture() {
        var config=new AuthStore.AppConfigState(true,10,1);
        var ledger=new MemberStore.LedgerState(1,1,10,"ACTIVE",1);
        var rows=List.of(new MemberStore.MemberState(11,1,"ACTIVE",MemberRole.OWNER,"ACTIVE","Owner",null,Instant.EPOCH));
        when(auth.lockAppConfig()).thenReturn(config); when(auth.readAppConfig()).thenReturn(config);
        when(members.lockLedger()).thenReturn(Optional.of(ledger)); when(members.readLedger()).thenReturn(Optional.of(ledger));
        when(members.lockMembers()).thenReturn(rows); when(members.readMembers()).thenReturn(rows);
        when(invites.insert(anyString(),eq(1L),any(),any())).thenReturn(51L);
    }
    @Test void omittedHoursDefaultsTo24AndOnlyDigestReachesStore() {
        var result=service.create(actor,null,"req");
        assertThat(result.expiresAt()).isEqualTo(now.plusSeconds(86400));
        assertThat(result.token()).matches("[A-Za-z0-9_-]{43}");
        verify(invites).insert(argThat(hash->hash.matches("[a-f0-9]{64}")&&!hash.equals(result.token())),eq(1L),eq(now),eq(now.plusSeconds(86400)));
    }
    @ParameterizedTest @ValueSource(ints={1,24,168})
    void acceptedHourBoundariesControlExpiry(int hours) {
        assertThat(service.create(actor,hours,"req").expiresAt()).isEqualTo(now.plusSeconds(hours*3600L));
    }
    @ParameterizedTest @ValueSource(ints={-1,0,169,Integer.MAX_VALUE})
    void invalidHoursNeverReachPersistence(int hours) {
        assertThatThrownBy(()->service.create(actor,hours,"req")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(auth,members,invites,audit);
    }
    @Test void pagingStatusAndSafeIdValidationRejectBadInputsBeforeRead() {
        for(int[] input:List.of(new int[]{0,20},new int[]{1,0},new int[]{1,51},new int[]{Integer.MAX_VALUE,50})) {
            assertThatThrownBy(()->service.list(actor,input[0],input[1],null)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(()->service.list(actor,1,20,"active")).isInstanceOf(BusinessException.class);
        for(long id:new long[]{0,-1,9007199254740992L,Long.MAX_VALUE})
            assertThatThrownBy(()->service.revoke(actor,id,"req")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(auth,members,invites,audit);
    }
    @Test void listUsesOneInstantReadOnlyRepeatableReadSnapshotAndBoundedOffset() {
        when(invites.count("EXPIRED",now)).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(4);
            return 4L;
        });
        when(invites.list("EXPIRED",now,20,20)).thenReturn(List.of(new InviteView(51,1,"Owner",now.minusSeconds(60),now,"EXPIRED",null,null)));
        var result=service.list(actor,2,20,"EXPIRED");
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.items().getFirst().status()).isEqualTo("EXPIRED");
        verify(auth,never()).lockAppConfig(); verify(members,never()).lockLedger(); verify(members,never()).lockMembers();
        verifyNoInteractions(audit);
    }
    @Test void acceptsAfterNetworkExchangeOutsideDatabaseTransaction() {
        when(wechat.exchange("code")).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            verifyNoInteractions(auth,members,invites,audit);
            return new WechatIdentity("dummy-openid",null);
        });
        when(auth.lockAppConfig()).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new AuthStore.AppConfigState(true,10,1);
        });
        when(members.lockUserByOpenid("dummy-openid")).thenReturn(Optional.of(new MemberStore.UserState(1,"ACTIVE")));
        assertThatThrownBy(()->service.accept(" code "," AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA ","req"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(ErrorCode.ALREADY_MEMBER));
        verify(wechat,times(1)).exchange("code");
        verifyNoInteractions(invites,audit);
    }
    @Test void malformedCodeOrTokenNeverCallsWechatOrDatabase() {
        for(String code:Arrays.asList(null,""," ","c".repeat(257)))
            assertThatThrownBy(()->service.accept(code,"A".repeat(43),"req")).isInstanceOf(BusinessException.class);
        for(String token:Arrays.asList(null,"", "A".repeat(42), "A".repeat(44)))
            assertThatThrownBy(()->service.accept("code",token,"req")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(wechat,auth,members,invites,audit);
    }
    @Test void wechatFailureDoesNotStartDatabaseWorkOrRetry() {
        when(wechat.exchange("code")).thenThrow(new BusinessException(ErrorCode.WECHAT_CODE_INVALID));
        assertThatThrownBy(()->service.accept("code","A".repeat(43),"req")).isInstanceOf(BusinessException.class);
        verify(wechat,times(1)).exchange("code"); verifyNoInteractions(auth,members,invites,audit);
    }
}
