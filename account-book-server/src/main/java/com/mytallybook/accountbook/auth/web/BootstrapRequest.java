package com.mytallybook.accountbook.auth.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BootstrapRequest(
        @NotBlank(message = "微信登录凭证不能为空")
        @Size(max = 256, message = "微信登录凭证长度不能超过256个字符")
        String code,

        @NotBlank(message = "初始化口令不能为空")
        @Size(min = 20, max = 256, message = "初始化口令长度必须为20至256个字符")
        String bootstrapKey
) {
}
