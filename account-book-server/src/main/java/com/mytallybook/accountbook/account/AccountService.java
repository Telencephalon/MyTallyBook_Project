package com.mytallybook.accountbook.account;

import com.mytallybook.accountbook.account.store.AccountStore;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.validation.BookkeepingValidation;
import com.mytallybook.accountbook.ledger.LedgerReadGuard;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

@Service
public class AccountService {
    private static final Set<String> TYPES = Set.of("CASH", "WECHAT", "BANK", "ALIPAY", "OTHER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final LedgerReadGuard readGuard;
    private final LedgerWriteGuard writeGuard;
    private final AccountStore store;
    private final AuditLogService audit;

    public AccountService(LedgerReadGuard readGuard, LedgerWriteGuard writeGuard,
                          AccountStore store, AuditLogService audit) {
        this.readGuard = readGuard;
        this.writeGuard = writeGuard;
        this.store = store;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountModels.AccountList list(CurrentUser actor, String status) {
        readGuard.requireActor(actor);
        String validatedStatus = status == null || status.isBlank()
                ? null : BookkeepingValidation.oneOf(status, STATUSES);
        return new AccountModels.AccountList(store.list(validatedStatus)
                .stream().map(AccountService::view).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountModels.AccountView get(CurrentUser actor, long id) {
        readGuard.requireActor(actor);
        return view(store.find(BookkeepingValidation.safeId(id))
                .orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Transactional
    public AccountModels.AccountView create(CurrentUser actor, AccountModels.AccountInput body,
                                            String requestId) {
        requireManager(writeGuard.lock().requireActor(actor));
        String name = BookkeepingValidation.name(body.name());
        String type = BookkeepingValidation.oneOf(body.accountType(), TYPES);
        String status = body.status() == null ? "ACTIVE" : BookkeepingValidation.oneOf(body.status(), STATUSES);
        var initialBalance = BookkeepingValidation.money(body.initialBalance(), true);
        int sortNo = BookkeepingValidation.sortNo(body.sortNo(), 0);
        long id;
        try {
            id = BookkeepingValidation.safeId(store.insert(name, type, initialBalance, sortNo, status));
        } catch (DataIntegrityViolationException exception) {
            throw error(ErrorCode.ACCOUNT_NAME_CONFLICT);
        }
        var result = view(store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_STATE_CHANGED)));
        append(actor, "ACCOUNT_CREATE", id, requestId,
                Map.of("name", name, "accountType", type, "status", status));
        return result;
    }

    @Transactional
    public AccountModels.AccountView update(CurrentUser actor, long rawId,
                                            AccountModels.AccountUpdate body, String requestId) {
        long id = BookkeepingValidation.safeId(rawId);
        long version = BookkeepingValidation.version(body.version());
        requireManager(writeGuard.lock().requireActor(actor));
        store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
        String name = BookkeepingValidation.name(body.name());
        String status = BookkeepingValidation.oneOf(body.status(), STATUSES);
        int sortNo = BookkeepingValidation.sortNo(body.sortNo(), 0);
        try {
            if (store.update(id, name, sortNo, status, version) != 1) {
                throw error(ErrorCode.RESOURCE_STATE_CHANGED);
            }
        } catch (DataIntegrityViolationException exception) {
            throw error(ErrorCode.ACCOUNT_NAME_CONFLICT);
        }
        append(actor, "ACCOUNT_UPDATE", id, requestId,
                Map.of("name", name, "status", status, "version", version));
        return view(store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_STATE_CHANGED)));
    }

    @Transactional
    public void delete(CurrentUser actor, long rawId, Long rawVersion, String requestId) {
        long id = BookkeepingValidation.safeId(rawId);
        long version = BookkeepingValidation.version(rawVersion);
        requireManager(writeGuard.lock().requireActor(actor));
        store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
        if (store.referenceCount(id) > 0) throw error(ErrorCode.RESOURCE_IN_USE);
        if (store.delete(id, version) != 1) throw error(ErrorCode.RESOURCE_STATE_CHANGED);
        append(actor, "ACCOUNT_DELETE", id, requestId, Map.of("version", version));
    }

    private void append(CurrentUser actor, String action, long id, String requestId, Map<String, ?> details) {
        audit.append(new AuditLogService.AuditEvent(1L, actor.userId(), action,
                "FUND_ACCOUNT", id, requestId, details));
    }

    private static void requireManager(MemberStore.MemberState actor) {
        if (actor.role() == MemberRole.MEMBER) throw error(ErrorCode.ACCESS_DENIED);
    }

    private static AccountModels.AccountView view(AccountStore.AccountRow row) {
        return new AccountModels.AccountView(BookkeepingValidation.safeId(row.id()), row.name(), row.accountType(),
                BookkeepingValidation.moneyText(row.initialBalance()),
                BookkeepingValidation.moneyText(row.currentBalance()), row.sortNo(), row.status(), row.version());
    }

    private static BusinessException error(ErrorCode code) {
        return new BusinessException(code);
    }
}
