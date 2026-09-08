package com.mytallybook.accountbook.auth.wechat;

@FunctionalInterface
public interface WechatSessionClient {

    WechatIdentity exchange(String code);
}
