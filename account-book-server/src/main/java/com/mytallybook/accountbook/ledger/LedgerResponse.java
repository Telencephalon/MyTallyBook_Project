package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.auth.store.AuthStore;

public record LedgerResponse(
        long id,
        String name,
        String currency,
        String timezone,
        int maxMembers
) {

    static LedgerResponse from(AuthStore.LedgerView ledger) {
        return new LedgerResponse(
                ledger.id(),
                ledger.name(),
                ledger.currency(),
                ledger.timezone(),
                ledger.maxMembers()
        );
    }
}
