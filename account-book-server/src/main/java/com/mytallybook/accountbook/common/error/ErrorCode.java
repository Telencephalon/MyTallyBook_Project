package com.mytallybook.accountbook.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    WECHAT_CODE_INVALID(HttpStatus.BAD_REQUEST, "微信登录凭证无效"),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "请先登录"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "没有操作权限"),
    WECHAT_LOGIN_BLOCKED(HttpStatus.FORBIDDEN, "微信登录暂不可用"),
    BOOTSTRAP_KEY_INVALID(HttpStatus.FORBIDDEN, "初始化口令无效"),
    USER_DISABLED(HttpStatus.FORBIDDEN, "用户已被停用"),
    USER_UNAVAILABLE(HttpStatus.FORBIDDEN, "用户不可用"),
    ALREADY_MEMBER(HttpStatus.CONFLICT, "已是账本成员"),
    INVITE_INVALID(HttpStatus.NOT_FOUND, "邀请无效"),
    INVITE_EXPIRED(HttpStatus.GONE, "邀请已到期"),
    INVITE_USED(HttpStatus.CONFLICT, "邀请已使用"),
    INVITE_REVOKED(HttpStatus.GONE, "邀请已撤销"),
    MEMBER_LIMIT_REACHED(HttpStatus.CONFLICT, "账本成员已满"),
    MEMBER_STATE_CHANGED(HttpStatus.CONFLICT, "成员状态已变化"),
    OWNER_TRANSFER_REQUIRED(HttpStatus.CONFLICT, "请先转让账本所有权"),
    ADMIN_DEMOTION_REQUIRED(HttpStatus.CONFLICT, "请先将管理员降为普通成员"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不受支持"),
    CONFLICT(HttpStatus.CONFLICT, "请求冲突"),
    ALREADY_INITIALIZED(HttpStatus.CONFLICT, "系统已经完成初始化"),
    SYSTEM_NOT_INITIALIZED(HttpStatus.CONFLICT, "系统尚未初始化"),
    LEDGER_STATE_CONFLICT(HttpStatus.CONFLICT, "账本状态冲突"),
    CATEGORY_NAME_CONFLICT(HttpStatus.CONFLICT, "同类型分类名称已存在"),
    ACCOUNT_NAME_CONFLICT(HttpStatus.CONFLICT, "账户名称已存在"),
    RESOURCE_IN_USE(HttpStatus.CONFLICT, "资源已有账单引用，请停用"),
    RESOURCE_STATE_CHANGED(HttpStatus.CONFLICT, "资源状态已变化，请刷新后重试"),
    ENTRY_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "该新增标识已被其他成员使用"),
    ENTRY_IDEMPOTENCY_DELETED(HttpStatus.CONFLICT, "该新增标识对应的账单已删除，请勿重复提交"),
    ENTRY_VERSION_CONFLICT(HttpStatus.CONFLICT, "账单已变化，请刷新后重试"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不受支持"),
    WECHAT_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "微信服务响应异常"),
    BOOTSTRAP_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "初始化口令尚未配置"),
    WECHAT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "微信服务暂时不可用"),
    WECHAT_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "微信服务响应超时"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
