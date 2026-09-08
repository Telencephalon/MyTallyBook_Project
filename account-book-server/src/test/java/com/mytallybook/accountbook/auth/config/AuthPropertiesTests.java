package com.mytallybook.accountbook.auth.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthPropertiesTests {

    private static final String PEPPER = "0123456789abcdef0123456789abcdef";

    @Test
    void acceptsBlankBootstrapKeyAfterInitialization() {
        assertThatCode(() -> new AuthProperties("", PEPPER, Duration.ofDays(30), 32))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsConfiguredBootstrapKeyShorterThanTwentyCharacters() {
        assertThatThrownBy(() -> new AuthProperties(
                "too-short",
                PEPPER,
                Duration.ofDays(30),
                32
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPepperShorterThanThirtyTwoCharacters() {
        assertThatThrownBy(() -> new AuthProperties(
                "",
                "short-pepper",
                Duration.ofDays(30),
                32
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnexpectedTokenLengthOrNonPositiveTtl() {
        assertThatThrownBy(() -> new AuthProperties("", PEPPER, Duration.ofDays(30), 16))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties("", PEPPER, Duration.ZERO, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsafeWechatConfiguration() {
        assertThatThrownBy(() -> new WechatProperties(
                "",
                "test-secret",
                URI.create("https://api.weixin.qq.com/sns/jscode2session"),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WechatProperties(
                "test-app-id",
                "test-secret",
                URI.create("http://api.weixin.qq.com/sns/jscode2session"),
                Duration.ofSeconds(3),
                Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
