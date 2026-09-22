package com.mytallybook.accountbook.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mytallybook.accountbook.auth.store.AuthStore;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record LedgerResponse(
        long id,
        String name,
        String currency,
        String timezone,
        Integer maxMembers
) {

    static LedgerResponse from(AuthStore.LedgerView ledger) {
        return new LedgerResponse(
                ledger.id(),
                ledger.name(),
                ledger.currency(),
                ledger.timezone(),
                null
        );
    }
}
