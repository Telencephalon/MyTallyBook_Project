package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BootstrapService {

    private static final int MIN_KEY_LENGTH = 20;
    private static final int MAX_KEY_LENGTH = 256;

    private final WechatSessionClient wechatSessionClient;
    private final SessionTokenService sessionTokenService;
    private final BootstrapTransactionService transactionService;

    public BootstrapService(
            WechatSessionClient wechatSessionClient,
            SessionTokenService sessionTokenService,
            BootstrapTransactionService transactionService
    ) {
        this.wechatSessionClient = wechatSessionClient;
        this.sessionTokenService = sessionTokenService;
        this.transactionService = transactionService;
    }

    public AuthResult bootstrap(String code, String bootstrapKey, String requestId) {
        String normalizedCode = requireCode(code);
        requireBootstrapKeyLength(bootstrapKey);
        WechatIdentity identity = wechatSessionClient.exchange(normalizedCode);
        IssuedSessionToken issued = sessionTokenService.issue();
        return transactionService.bootstrap(identity, bootstrapKey, issued, requestId);
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank() || code.trim().length() > 256) {
            throw validation("code");
        }
        return code.trim();
    }

    private static void requireBootstrapKeyLength(String bootstrapKey) {
        if (bootstrapKey == null
                || bootstrapKey.length() < MIN_KEY_LENGTH
                || bootstrapKey.length() > MAX_KEY_LENGTH) {
            throw validation("bootstrapKey");
        }
    }

    private static BusinessException validation(String field) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(),
                Map.of(field, "invalid value")
        );
    }
}
