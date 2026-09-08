package com.mytallybook.accountbook.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        Object details,
        String requestId,
        OffsetDateTime timestamp
) {

    public static ApiError of(String code, String message, Object details, String requestId) {
        return new ApiError(
                code,
                message,
                details,
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }
}
