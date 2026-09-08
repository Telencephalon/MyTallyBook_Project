package com.mytallybook.accountbook.common.validation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;

public abstract class StrictRequest {
    @JsonAnySetter
    public final void rejectUnknown(String name, JsonNode value) {
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的请求字段: " + name);
    }
    protected static String string(JsonNode value) {
        if (value == null || !value.isString()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value.stringValue();
    }
    protected static Integer integer(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value.intValue();
    }
    protected static Long longInteger(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value.longValue();
    }
    protected static String nullableString(JsonNode value) {
        if (value == null || value.isNull()) return null;
        return string(value);
    }
}
