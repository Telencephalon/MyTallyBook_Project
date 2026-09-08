package com.mytallybook.accountbook.user;

import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    ApiResponse<UserProfileResponse> get(
            @AuthenticationPrincipal CurrentUser currentUser,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(userService.get(currentUser), requestId);
    }

    @RequestMapping(method = {RequestMethod.PATCH, RequestMethod.PUT})
    ApiResponse<UserProfileResponse> update(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestBody UpdateProfileRequest body,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(
                userService.update(currentUser, body, requestId),
                requestId
        );
    }
}
