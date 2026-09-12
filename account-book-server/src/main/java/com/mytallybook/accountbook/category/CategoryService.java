package com.mytallybook.accountbook.category;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.category.store.CategoryStore;
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
public class CategoryService {
    private static final Set<String> TYPES = Set.of("INCOME", "EXPENSE", "BOTH");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");

    private final LedgerReadGuard readGuard;
    private final LedgerWriteGuard writeGuard;
    private final CategoryStore store;
    private final AuditLogService audit;

    public CategoryService(LedgerReadGuard readGuard, LedgerWriteGuard writeGuard,
                           CategoryStore store, AuditLogService audit) {
        this.readGuard = readGuard;
        this.writeGuard = writeGuard;
        this.store = store;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CategoryModels.CategoryList list(CurrentUser actor, String entryType, String status) {
        readGuard.requireActor(actor);
        String validatedType = optional(entryType, TYPES);
        String validatedStatus = optional(status, STATUSES);
        return new CategoryModels.CategoryList(store.list(validatedType, validatedStatus)
                .stream().map(CategoryService::view).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CategoryModels.CategoryView get(CurrentUser actor, long id) {
        readGuard.requireActor(actor);
        return view(store.find(BookkeepingValidation.safeId(id))
                .orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    @Transactional
    public CategoryModels.CategoryView create(CurrentUser actor, CategoryModels.CategoryInput body,
                                              String requestId) {
        requireManager(writeGuard.lock().requireActor(actor));
        String type = BookkeepingValidation.oneOf(body.entryType(), TYPES);
        String name = BookkeepingValidation.name(body.name());
        String icon = BookkeepingValidation.nullableIcon(body.icon());
        String color = BookkeepingValidation.nullableColor(body.color());
        String status = body.status() == null ? "ACTIVE" : BookkeepingValidation.oneOf(body.status(), STATUSES);
        int sortNo = BookkeepingValidation.sortNo(body.sortNo(), 0);
        long id;
        try {
            id = BookkeepingValidation.safeId(store.insert(type, name, icon, color, sortNo, status));
        } catch (DataIntegrityViolationException exception) {
            throw error(ErrorCode.CATEGORY_NAME_CONFLICT);
        }
        var result = view(store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_STATE_CHANGED)));
        append(actor, "CATEGORY_CREATE", id, requestId,
                Map.of("entryType", type, "name", name, "status", status));
        return result;
    }

    @Transactional
    public CategoryModels.CategoryView update(CurrentUser actor, long rawId,
                                              CategoryModels.CategoryUpdate body, String requestId) {
        long id = BookkeepingValidation.safeId(rawId);
        requireManager(writeGuard.lock().requireActor(actor));
        var original = store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
        String type = body.entryType() == null ? original.entryType() : BookkeepingValidation.oneOf(body.entryType(), TYPES);
        String name = BookkeepingValidation.name(body.name());
        String icon = BookkeepingValidation.nullableIcon(body.icon());
        String color = BookkeepingValidation.nullableColor(body.color());
        String status = BookkeepingValidation.oneOf(body.status(), STATUSES);
        int sortNo = BookkeepingValidation.sortNo(body.sortNo(), 0);
        try {
            if (store.update(id, type, name, icon, color, sortNo, status) != 1) {
                throw error(ErrorCode.RESOURCE_STATE_CHANGED);
            }
        } catch (DataIntegrityViolationException exception) {
            throw error(ErrorCode.CATEGORY_NAME_CONFLICT);
        }
        append(actor, "CATEGORY_UPDATE", id, requestId, Map.of("entryType", type, "name", name, "status", status));
        return view(store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_STATE_CHANGED)));
    }

    @Transactional
    public void delete(CurrentUser actor, long rawId, String requestId) {
        long id = BookkeepingValidation.safeId(rawId);
        requireManager(writeGuard.lock().requireActor(actor));
        store.find(id).orElseThrow(() -> error(ErrorCode.RESOURCE_NOT_FOUND));
        if (store.referenceCount(id) > 0) throw error(ErrorCode.RESOURCE_IN_USE);
        store.purgeDeletedReferences(id);
        if (store.delete(id) != 1) throw error(ErrorCode.RESOURCE_STATE_CHANGED);
        append(actor, "CATEGORY_DELETE", id, requestId, Map.of());
    }

    private void append(CurrentUser actor, String action, long id, String requestId, Map<String, ?> details) {
        audit.append(new AuditLogService.AuditEvent(1L, actor.userId(), action,
                "CATEGORY", id, requestId, details));
    }

    private static void requireManager(MemberStore.MemberState actor) {
        if (actor.role() == MemberRole.MEMBER) throw error(ErrorCode.ACCESS_DENIED);
    }

    private static String optional(String value, Set<String> allowed) {
        return value == null || value.isBlank() ? null : BookkeepingValidation.oneOf(value, allowed);
    }

    private static CategoryModels.CategoryView view(CategoryStore.CategoryRow row) {
        return new CategoryModels.CategoryView(BookkeepingValidation.safeId(row.id()), row.entryType(), row.name(),
                row.icon(), row.color(), row.sortNo(), row.systemDefault(), row.status());
    }

    private static BusinessException error(ErrorCode code) {
        return new BusinessException(code);
    }
}
