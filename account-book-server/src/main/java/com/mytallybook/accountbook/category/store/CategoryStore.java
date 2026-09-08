package com.mytallybook.accountbook.category.store;

import java.util.List;
import java.util.Optional;

public interface CategoryStore {
    List<CategoryRow> list(String entryType, String status);
    Optional<CategoryRow> find(long id);
    long insert(String entryType, String name, String icon, String color, int sortNo, String status);
    int update(long id, String name, String icon, String color, int sortNo, String status);
    long referenceCount(long id);
    int delete(long id);

    record CategoryRow(long id, String entryType, String name, String icon, String color,
                       int sortNo, boolean systemDefault, String status) {}
}
