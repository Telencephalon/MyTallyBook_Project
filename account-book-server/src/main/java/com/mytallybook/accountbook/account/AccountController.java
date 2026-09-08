package com.mytallybook.accountbook.account;

import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AccountModels.AccountList> list(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        return ApiResponse.success(service.list(actor, status), RequestIdFilter.getRequestId(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AccountModels.AccountView> get(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(service.get(actor, id), RequestIdFilter.getRequestId(request));
    }

    @PostMapping
    public ApiResponse<AccountModels.AccountView> create(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestBody AccountModels.CreateRequest body,
            HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.create(actor, body.value(), requestId), requestId);
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ApiResponse<AccountModels.AccountView> update(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable long id,
            @RequestBody AccountModels.UpdateRequest body,
            HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.update(actor, id, body.value(), requestId), requestId);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal CurrentUser actor,
            @PathVariable long id,
            @RequestParam Long version,
            HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        service.delete(actor, id, version, requestId);
        return ApiResponse.success(null, requestId);
    }
}
