package com.mytallybook.accountbook.member;

import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {
    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MemberList> list(@AuthenticationPrincipal CurrentUser actor, HttpServletRequest request) {
        return ApiResponse.success(service.list(actor), RequestIdFilter.getRequestId(request));
    }

    @PreAuthorize("hasRole('OWNER')")
    @RequestMapping(value = "/{memberId}/role", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ApiResponse<MemberView> changeRole(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable long memberId, @RequestBody ChangeRoleRequest body, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.changeRole(actor, memberId, body.role(), requestId), requestId);
    }

    @DeleteMapping("/{memberId}")
    public ApiResponse<RemovedMember> remove(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable long memberId, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.remove(actor, memberId, requestId), requestId);
    }

    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{memberId}/transfer-ownership")
    public ApiResponse<OwnershipTransfer> transfer(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable long memberId, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.transfer(actor, memberId, requestId), requestId);
    }
}
