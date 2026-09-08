package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.auth.web.AuthStateResponse;
import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
public class InviteAcceptController {
    private final InviteService service;
    public InviteAcceptController(InviteService service) { this.service=service; }
    @PostMapping("/api/v1/auth/invites/accept")
    public ApiResponse<AuthStateResponse> accept(@Valid @RequestBody AcceptInviteRequest body, HttpServletRequest request) {
        String id=RequestIdFilter.getRequestId(request);
        return ApiResponse.success(AuthStateResponse.from(service.accept(body.code(),body.inviteToken(),id)),id);
    }
}
