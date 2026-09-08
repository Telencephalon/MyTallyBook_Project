package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {

    private final AuthStore authStore;

    public LedgerService(AuthStore authStore) {
        this.authStore = authStore;
    }

    public LedgerResponse get(CurrentUser currentUser) {
        return authStore.findLedger(currentUser.ledgerId())
                .map(LedgerResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
