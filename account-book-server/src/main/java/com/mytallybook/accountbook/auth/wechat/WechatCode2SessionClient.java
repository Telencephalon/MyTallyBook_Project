package com.mytallybook.accountbook.auth.wechat;

import com.mytallybook.accountbook.auth.config.WechatProperties;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;

public final class WechatCode2SessionClient implements WechatSessionClient {

    private static final Logger log = LoggerFactory.getLogger(WechatCode2SessionClient.class);
    private static final JsonMapper RESPONSE_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final RestClient restClient;
    private final WechatProperties properties;

    public WechatCode2SessionClient(
            RestClient.Builder restClientBuilder,
            WechatProperties properties
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder must not be null")
                .baseUrl(properties.sessionEndpoint().toString())
                .build();
    }

    @Override
    public WechatIdentity exchange(String code) {
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .toEntity(byte[].class);
            return mapResponse(parseResponse(
                    response.getBody(),
                    response.getHeaders().getContentType()
            ));
        } catch (BusinessException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (hasTimeoutCause(exception)) {
                logClientFailure("timeout", exception);
                throw new BusinessException(ErrorCode.WECHAT_SERVICE_TIMEOUT);
            }
            logClientFailure("resource_access", exception);
            throw new BusinessException(ErrorCode.WECHAT_SERVICE_ERROR);
        } catch (RestClientException exception) {
            if (exception instanceof RestClientResponseException responseException) {
                log.warn(
                        "WeChat code2Session failure, failureType=http_status, "
                                + "exceptionType={}, upstreamHttpStatus={}",
                        exception.getClass().getName(),
                        responseException.getStatusCode().value()
                );
            } else {
                logClientFailure("rest_client", exception);
            }
            throw new BusinessException(ErrorCode.WECHAT_SERVICE_ERROR);
        }
    }

    private Code2SessionResponse parseResponse(byte[] responseBody, MediaType contentType) {
        if (responseBody == null || responseBody.length == 0) {
            return null;
        }
        try {
            return RESPONSE_MAPPER.readValue(responseBody, Code2SessionResponse.class);
        } catch (JacksonException exception) {
            log.warn(
                    "WeChat code2Session failure, failureType=rest_client, "
                            + "contentTypeCategory={}, exceptionType={}",
                    contentTypeCategory(contentType),
                    exception.getClass().getName()
            );
            throw new BusinessException(ErrorCode.WECHAT_SERVICE_ERROR);
        }
    }

    private static String contentTypeCategory(MediaType contentType) {
        if (contentType == null) {
            return "missing";
        }
        if (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return "json";
        }
        if (MediaType.TEXT_PLAIN.isCompatibleWith(contentType)) {
            return "text_plain";
        }
        if (MediaType.APPLICATION_OCTET_STREAM.isCompatibleWith(contentType)) {
            return "octet_stream";
        }
        if (MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
            return "html";
        }
        return "other";
    }

    private WechatIdentity mapResponse(Code2SessionResponse response) {
        if (response == null) {
            log.warn("WeChat code2Session failure, failureType=null_response");
            throw new BusinessException(ErrorCode.WECHAT_SERVICE_ERROR);
        }
        if (response.errcode() != null && response.errcode() != 0) {
            log.warn(
                    "WeChat code2Session failure, failureType=wechat_error, wechatErrcode={}",
                    response.errcode()
            );
            throw new BusinessException(mapErrorCode(response.errcode()));
        }
        if (response.openid() == null || response.openid().isBlank()) {
            log.warn("WeChat code2Session failure, failureType=missing_identity");
            throw new BusinessException(ErrorCode.WECHAT_SERVICE_ERROR);
        }
        return new WechatIdentity(response.openid(), response.unionid());
    }

    private ErrorCode mapErrorCode(int wechatErrorCode) {
        return switch (wechatErrorCode) {
            case 40029 -> ErrorCode.WECHAT_CODE_INVALID;
            case 40226 -> ErrorCode.WECHAT_LOGIN_BLOCKED;
            case -1, 45011 -> ErrorCode.WECHAT_SERVICE_UNAVAILABLE;
            default -> ErrorCode.WECHAT_SERVICE_ERROR;
        };
    }

    private void logClientFailure(String failureType, RestClientException exception) {
        Throwable cause = exception.getCause();
        log.warn(
                "WeChat code2Session failure, failureType={}, exceptionType={}, causeType={}",
                failureType,
                exception.getClass().getName(),
                cause == null ? "<none>" : cause.getClass().getName()
        );
    }

    private boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record Code2SessionResponse(
            String openid,
            String unionid,
            Integer errcode,
            String errmsg
    ) {
    }
}
