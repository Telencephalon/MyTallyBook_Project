package com.mytallybook.accountbook.auth.wechat;

public record WechatIdentity(
        String openid,
        String unionid
) {
}
