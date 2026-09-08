package com.mytallybook.accountbook.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.wechat")
public record WechatProperties(
        String appId,
        String appSecret,
        URI sessionEndpoint,
        Duration connectTimeout,
        Duration readTimeout
) {

    public WechatProperties {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("appId must not be blank");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("appSecret must not be blank");
        }
        if (sessionEndpoint == null
                || !"https".equalsIgnoreCase(sessionEndpoint.getScheme())
                || sessionEndpoint.getHost() == null) {
            throw new IllegalArgumentException("sessionEndpoint must be an HTTPS URI");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
