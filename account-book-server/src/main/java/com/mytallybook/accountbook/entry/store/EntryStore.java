package com.mytallybook.accountbook.entry.store;

import com.mytallybook.accountbook.entry.EntryFilters;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EntryStore {
    List<EntryRow> list(EntryFilters filters);
    long count(EntryFilters filters);
    Optional<EntryRow> find(long id);
    Optional<EntryRow> findByClientRequestId(String clientRequestId);
    Optional<CategoryReference> findCategory(long id);
    Optional<AccountReference> findAccount(long id);
    List<CreatorRow> creators();
    long insert(String entryType, BigDecimal amount, long categoryId, long accountId,
                LocalDate entryDate, String note, String clientRequestId, long actorUserId);
    int update(long id, String entryType, BigDecimal amount, long categoryId, long accountId,
               LocalDate entryDate, String note, long actorUserId, Instant updatedAt, long version);
    int softDelete(long id, Instant deletedAt, long actorUserId, long version);

    record EntryRow(long id, String entryType, BigDecimal amount,
                    long categoryId, String categoryName, String categoryStatus,
                    long accountId, String accountName, String accountStatus,
                    LocalDate entryDate, String note, long createdBy, String creatorName,
                    Instant createdAt, Instant updatedAt,
                    Instant deletedAt, String clientRequestId, long version) {}
    record CategoryReference(long id, String entryType, String name, String status) {}
    record AccountReference(long id, String name, String status) {}
    record CreatorRow(long userId, String displayName) {}
}
