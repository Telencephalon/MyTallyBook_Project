package com.mytallybook.accountbook.account.store;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountStore {
    List<AccountRow> list(String status);
    List<AccountRow> list(String status, long createdBy);
    Optional<AccountRow> find(long id);
    Optional<AccountRow> find(long id, long createdBy);
    long insert(String name, String type, BigDecimal initialBalance, int sortNo, String status);
    int update(long id, String name, int sortNo, String status, long version);
    long referenceCount(long id);
    int delete(long id, long version);

    record AccountRow(long id, String name, String accountType, BigDecimal initialBalance,
                      BigDecimal currentBalance, int sortNo, String status, long version) {}
}
