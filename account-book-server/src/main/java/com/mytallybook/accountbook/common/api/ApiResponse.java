package com.mytallybook.accountbook.common.api;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record ApiResponse<T>(
        String code,
        String message,
        T data,
        String requestId,
        OffsetDateTime timestamp
) {

    public static <T> ApiResponse<T> success(T data, String requestId) {
        return new ApiResponse<>(
                "OK",
                "success",
                data,
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }
}
