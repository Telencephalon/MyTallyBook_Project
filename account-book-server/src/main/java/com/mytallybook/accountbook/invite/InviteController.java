package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/invites")
@PreAuthorize("hasAnyRole('OWNER','ADMIN')")
public class InviteController {
    private final InviteService service;
    public InviteController(InviteService service) { this.service=service; }
    @PostMapping
    public ApiResponse<CreatedInvite> create(@AuthenticationPrincipal CurrentUser actor,
            @Valid @RequestBody CreateInviteRequest body, HttpServletRequest request) {
        String id=RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.create(actor,body.expiresInHours(),id),id);
    }
    @GetMapping
    public ApiResponse<InvitePage> list(@AuthenticationPrincipal CurrentUser actor,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String status, HttpServletRequest request) {
        return ApiResponse.success(service.list(actor,page,pageSize,status),RequestIdFilter.getRequestId(request));
    }
    @DeleteMapping("/{inviteId}")
    public ApiResponse<RevokedInvite> revoke(@AuthenticationPrincipal CurrentUser actor,
            @PathVariable long inviteId, HttpServletRequest request) {
        String id=RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.revoke(actor,inviteId,id),id);
    }
}
