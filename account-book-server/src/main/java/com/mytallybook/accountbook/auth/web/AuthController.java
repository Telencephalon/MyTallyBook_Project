package com.mytallybook.accountbook.auth.web;

import com.mytallybook.accountbook.auth.service.AuthService;
import com.mytallybook.accountbook.auth.service.BootstrapService;
import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final BootstrapService bootstrapService;

    public AuthController(AuthService authService, BootstrapService bootstrapService) {
        this.authService = authService;
        this.bootstrapService = bootstrapService;
    }

    @PostMapping("/wechat/login")
    ApiResponse<AuthStateResponse> login(
            @Valid @RequestBody WechatLoginRequest body,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(
                AuthStateResponse.from(authService.login(body.code(), requestId)),
                requestId
        );
    }

    @PostMapping("/bootstrap")
    ApiResponse<AuthStateResponse> bootstrap(
            @Valid @RequestBody BootstrapRequest body,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(
                AuthStateResponse.from(bootstrapService.bootstrap(
                        body.code(),
                        body.bootstrapKey(),
                        requestId
                )),
                requestId
        );
    }

    @PostMapping("/logout")
    ApiResponse<LogoutResponse> logout(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @AuthenticationPrincipal CurrentUser currentUser,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.getRequestId(request);
        String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
        authService.logout(rawToken, currentUser, requestId);
        return ApiResponse.success(LogoutResponse.loggedOut(), requestId);
    }
}
