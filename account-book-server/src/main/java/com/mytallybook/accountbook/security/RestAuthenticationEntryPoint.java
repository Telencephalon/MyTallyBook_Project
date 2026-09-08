package com.mytallybook.accountbook.security;

import com.mytallybook.accountbook.common.api.ApiError;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        writeError(request, response, ErrorCode.AUTHENTICATION_REQUIRED);
    }

    void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        response.setStatus(errorCode.httpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiError.of(
                        errorCode.name(),
                        errorCode.defaultMessage(),
                        null,
                        RequestIdFilter.getRequestId(request)
                )
        );
    }
}
