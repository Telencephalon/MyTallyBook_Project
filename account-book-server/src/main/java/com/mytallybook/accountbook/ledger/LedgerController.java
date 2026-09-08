package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping
    ApiResponse<LedgerResponse> get(
            @AuthenticationPrincipal CurrentUser currentUser,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(ledgerService.get(currentUser), requestId);
    }
}
