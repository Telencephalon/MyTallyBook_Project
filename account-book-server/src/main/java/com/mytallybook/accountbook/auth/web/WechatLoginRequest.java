package com.mytallybook.accountbook.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WechatLoginRequest(
        @NotBlank(message = "微信登录凭证不能为空")
        @Size(max = 256, message = "微信登录凭证长度不能超过256个字符")
        String code
) {
}
