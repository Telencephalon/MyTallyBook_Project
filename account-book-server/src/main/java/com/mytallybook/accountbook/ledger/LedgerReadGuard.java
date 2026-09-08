package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.CurrentUser;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;

@Component
public class LedgerReadGuard {
    private final AuthStore auth;
    private final MemberStore members;
    private final LedgerWriteGuard invariant;
    public LedgerReadGuard(AuthStore auth, MemberStore members, LedgerWriteGuard invariant) {
        this.auth=auth; this.members=members; this.invariant=invariant;
    }
    public MemberStore.MemberState requireActor(CurrentUser actor) {
        AuthStore.AppConfigState config;
        try { config=auth.readAppConfig(); } catch (EmptyResultDataAccessException exception) { throw conflict(); }
        var ledger=members.readLedger().orElseThrow(LedgerReadGuard::conflict);
        var rows=members.readMembers();
        if (!config.initialized() || config.maxUsers()<1 || config.maxUsers()>10 || ledger.id()!=1
                || !"ACTIVE".equals(ledger.status()) || ledger.maxMembers()<1 || ledger.maxMembers()>10) throw conflict();
        invariant.assertOwnerInvariant(ledger, rows);
        return new LedgerWriteGuard.LockedLedger(config,ledger,rows).requireActor(actor);
    }
    private static BusinessException conflict(){ return new BusinessException(ErrorCode.LEDGER_STATE_CONFLICT); }
}
