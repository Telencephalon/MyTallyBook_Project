package com.mytallybook.accountbook.entry;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.validation.BookkeepingValidation;
import com.mytallybook.accountbook.entry.store.EntryStore;
import com.mytallybook.accountbook.ledger.LedgerReadGuard;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

@Service
public class EntryService {
    private static final Set<String> TYPES = Set.of("INCOME", "EXPENSE");
    private final LedgerReadGuard readGuard;
    private final LedgerWriteGuard writeGuard;
    private final EntryStore store;
    private final AuditLogService audit;

    public EntryService(LedgerReadGuard readGuard, LedgerWriteGuard writeGuard,
                        EntryStore store, AuditLogService audit) {
        this.readGuard = readGuard;
        this.writeGuard = writeGuard;
        this.store = store;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryModels.EntryPage list(CurrentUser actor, EntryFilters filters) {
        var member = readGuard.requireActor(actor);
        var items = store.list(filters).stream().map(row -> view(row, member)).toList();
        return new EntryModels.EntryPage(items, filters.page(), filters.pageSize(), store.count(filters));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryModels.EntryView get(CurrentUser actor, long rawId) {
        long id = BookkeepingValidation.safeId(rawId);
        var member = readGuard.requireActor(actor);
        return view(store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND)), member);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryModels.CreatorList creators(CurrentUser actor) {
        readGuard.requireActor(actor);
        return new EntryModels.CreatorList(store.creators().stream()
                .map(row -> new EntryModels.CreatorOption(BookkeepingValidation.safeId(row.userId()), row.displayName()))
                .toList());
    }

    @Transactional
    public EntryModels.EntryView create(CurrentUser actor, EntryModels.EntryInput body, String requestId) {
        var member = writeGuard.lock().requireActor(actor);
        String clientRequestId = BookkeepingValidation.uuid(body.clientRequestId());
        var existing = store.findByClientRequestId(clientRequestId);
        if (existing.isPresent()) {
            var row = existing.get();
            if (row.deletedAt() != null) throw error(ErrorCode.ENTRY_IDEMPOTENCY_DELETED);
            if (row.createdBy() != member.userId()) throw error(ErrorCode.ENTRY_IDEMPOTENCY_CONFLICT);
            return view(row, member);
        }
        var values = validate(body.entryType(), body.amount(), body.categoryId(), body.accountId(),
                body.entryDate(), body.note(), body.personName(), null);
        long id = BookkeepingValidation.safeId(store.insert(values.entryType, values.amount, values.categoryId,
                values.accountId, values.entryDate, values.note, clientRequestId, member.userId(), values.personName));
        var result = view(store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_STATE_CHANGED)), member);
        append(member.userId(), "ENTRY_CREATE", id, requestId, Map.of(
                "entryType", values.entryType, "amount", values.amount.toPlainString(),
                "categoryId", values.categoryId, "accountId", values.accountId,
                "entryDate", values.entryDate.toString()));
        return result;
    }

    @Transactional
    public EntryModels.EntryView update(CurrentUser actor, long rawId, EntryModels.EntryUpdate body, String requestId) {
        long id = BookkeepingValidation.safeId(rawId);
        long version = BookkeepingValidation.version(body.version());
        var member = writeGuard.lock().requireActor(actor);
        var current = store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
        requireMutation(member, current);
        var values = validate(body.entryType(), body.amount(), body.categoryId(), body.accountId(),
                body.entryDate(), body.note(), body.personNameProvided() ? body.personName() : current.personName(), current);
        if (store.update(id, values.entryType, values.amount, values.categoryId, values.accountId,
                values.entryDate, values.note, member.userId(), Instant.now(), version, values.personName) != 1) {
            throw error(ErrorCode.ENTRY_VERSION_CONFLICT);
        }
        append(member.userId(), "ENTRY_UPDATE", id, requestId, Map.of(
                "entryType", values.entryType, "amount", values.amount.toPlainString(),
                "categoryId", values.categoryId, "accountId", values.accountId,
                "entryDate", values.entryDate.toString(), "version", version));
        return view(store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_STATE_CHANGED)), member);
    }

    @Transactional
    public void delete(CurrentUser actor, long rawId, Long rawVersion, String requestId) {
        long id = BookkeepingValidation.safeId(rawId);
        long version = BookkeepingValidation.version(rawVersion);
        var member = writeGuard.lock().requireActor(actor);
        var current = store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
        requireMutation(member, current);
        if (store.softDelete(id, Instant.now(), member.userId(), version) != 1) {
            throw error(ErrorCode.ENTRY_VERSION_CONFLICT);
        }
        append(member.userId(), "ENTRY_DELETE", id, requestId, Map.of("version", version));
    }

    private Values validate(String rawType, String rawAmount, long rawCategoryId, long rawAccountId,
                            String rawDate, String rawNote, String rawPersonName, EntryStore.EntryRow original) {
        String entryType = BookkeepingValidation.oneOf(rawType, TYPES);
        BigDecimal amount = BookkeepingValidation.money(rawAmount, false);
        long categoryId = BookkeepingValidation.safeId(rawCategoryId);
        long accountId = BookkeepingValidation.safeId(rawAccountId);
        LocalDate entryDate = BookkeepingValidation.date(rawDate);
        String note = BookkeepingValidation.entryNote(rawNote);
        String personName = BookkeepingValidation.personName(rawPersonName);
        var category = store.findCategory(categoryId).orElseThrow(() -> error(ErrorCode.VALIDATION_FAILED));
        var account = store.findAccount(accountId).orElseThrow(() -> error(ErrorCode.VALIDATION_FAILED));
        boolean unchangedCategory = original != null && original.categoryId() == categoryId
                && original.entryType().equals(entryType);
        boolean unchangedAccount = original != null && original.accountId() == accountId;
        if (!category.entryType().equals(entryType)
                || (!unchangedCategory && !"ACTIVE".equals(category.status()))
                || (!unchangedAccount && !"ACTIVE".equals(account.status()))) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
        return new Values(entryType, amount, categoryId, accountId, entryDate, note, personName);
    }

    private static void requireMutation(MemberStore.MemberState actor, EntryStore.EntryRow row) {
        if (actor.role() == MemberRole.MEMBER && row.createdBy() != actor.userId()) {
            throw error(ErrorCode.ACCESS_DENIED);
        }
    }

    private EntryModels.EntryView view(EntryStore.EntryRow row, MemberStore.MemberState actor) {
        boolean allowed = actor.role() != MemberRole.MEMBER || row.createdBy() == actor.userId();
        return new EntryModels.EntryView(BookkeepingValidation.safeId(row.id()), row.entryType(),
                BookkeepingValidation.moneyText(row.amount()), BookkeepingValidation.safeId(row.categoryId()),
                row.categoryName(), row.categoryStatus(), BookkeepingValidation.safeId(row.accountId()),
                row.accountName(), row.accountStatus(), row.entryDate().toString(), row.note(),
                BookkeepingValidation.safeId(row.createdBy()), row.creatorName(), row.createdAt(), row.updatedAt(),
                BookkeepingValidation.version(row.version()), allowed, allowed, row.clientRequestId(), row.personName());
    }

    private void append(long actorId, String action, long id, String requestId, Map<String, ?> details) {
        audit.append(new AuditLogService.AuditEvent(1L, actorId, action, "BOOK_ENTRY", id, requestId, details));
    }

    private record Values(String entryType, BigDecimal amount, long categoryId, long accountId,
                          LocalDate entryDate, String note, String personName) {}
    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
