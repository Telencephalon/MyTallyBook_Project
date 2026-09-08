package com.mytallybook.accountbook.member;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.MemberRole;
import tools.jackson.databind.JsonNode;

public final class ChangeRoleRequest {
    private MemberRole role;

    public void setRole(JsonNode value) {
        if (value == null || !value.isString()
                || !("ADMIN".equals(value.stringValue()) || "MEMBER".equals(value.stringValue()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        role = MemberRole.valueOf(value.stringValue());
    }

    public MemberRole role() {
        return role;
    }
}
