package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.common.error.*;
import tools.jackson.databind.JsonNode;
public final class CreateInviteRequest {
    private Integer expiresInHours;
    public void setExpiresInHours(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        expiresInHours = value.intValue();
    }
    public Integer expiresInHours() { return expiresInHours; }
}
