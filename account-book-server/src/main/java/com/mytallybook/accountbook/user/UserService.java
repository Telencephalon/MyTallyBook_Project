package com.mytallybook.accountbook.user;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class UserService {

    private static final int MAX_NICKNAME_LENGTH = 64;
    private static final int MAX_AVATAR_URL_LENGTH = 512;

    private final AuthStore authStore;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public UserService(AuthStore authStore, AuditLogService auditLogService, Clock clock) {
        this.authStore = authStore;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    public UserProfileResponse get(CurrentUser currentUser) {
        return UserProfileResponse.from(requireProfile(currentUser.userId()));
    }

    @Transactional
    public UserProfileResponse update(
            CurrentUser currentUser,
            UpdateProfileRequest request,
            String requestId
    ) {
        NormalizedUpdate update = validateAndNormalize(request);
        authStore.lockAppConfig();
        var membership = authStore.lockLoginMembership(currentUser.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"ACTIVE".equals(membership.userStatus())
                || !"ACTIVE".equals(membership.memberStatus())
                || !"ACTIVE".equals(membership.ledgerStatus())
                || membership.role() == null
                || membership.userId() != currentUser.userId()
                || membership.ledgerId() == null || membership.ledgerId() != 1
                || membership.ledgerId() != currentUser.ledgerId()
                || membership.memberId() == null || membership.memberId() != currentUser.memberId()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        AuthStore.UserProfileView existing = requireProfile(currentUser.userId());
        boolean nicknameChanged = update.nicknamePresent()
                && !Objects.equals(existing.nickname(), update.nickname());
        boolean avatarChanged = update.avatarUrlPresent()
                && !Objects.equals(existing.avatarUrl(), update.avatarUrl());

        authStore.updateUserProfile(
                currentUser.userId(),
                update.nicknamePresent(),
                update.nickname(),
                update.avatarUrlPresent(),
                update.avatarUrl(),
                clock.instant()
        );
        auditLogService.append(new AuditLogService.AuditEvent(
                currentUser.ledgerId(),
                currentUser.userId(),
                "USER_PROFILE_UPDATE",
                "APP_USER",
                currentUser.userId(),
                requestId,
                Map.of(
                        "nicknameChanged", nicknameChanged,
                        "avatarChanged", avatarChanged
                )
        ));
        return UserProfileResponse.from(requireProfile(currentUser.userId()));
    }

    private AuthStore.UserProfileView requireProfile(long userId) {
        return authStore.findUserProfile(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static NormalizedUpdate validateAndNormalize(UpdateProfileRequest request) {
        if (request == null || (!request.nicknamePresent() && !request.avatarUrlPresent())) {
            throw validation(Map.of("request", "至少提供一个可更新字段"));
        }

        Map<String, String> errors = new LinkedHashMap<>();
        String nickname = null;
        if (request.nicknamePresent()) {
            if (request.nickname() == null) {
                errors.put("nickname", "昵称不能为null");
            } else {
                nickname = request.nickname().trim();
                if (nickname.isEmpty() || nickname.length() > MAX_NICKNAME_LENGTH) {
                    errors.put("nickname", "昵称长度必须为1至64个字符");
                }
            }
        }

        String avatarUrl = null;
        if (request.avatarUrlPresent() && request.avatarUrl() != null) {
            avatarUrl = request.avatarUrl().trim();
            if (avatarUrl.isEmpty()) {
                avatarUrl = null;
            } else if (!isValidAvatarUrl(avatarUrl)) {
                errors.put("avatarUrl", "头像地址必须是长度不超过512的HTTPS地址");
            }
        }

        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return new NormalizedUpdate(
                request.nicknamePresent(),
                nickname,
                request.avatarUrlPresent(),
                avatarUrl
        );
    }

    private static boolean isValidAvatarUrl(String avatarUrl) {
        if (avatarUrl.length() > MAX_AVATAR_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = URI.create(avatarUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static BusinessException validation(Map<String, String> details) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                details
        );
    }

    private record NormalizedUpdate(
            boolean nicknamePresent,
            String nickname,
            boolean avatarUrlPresent,
            String avatarUrl
    ) {
    }
}
