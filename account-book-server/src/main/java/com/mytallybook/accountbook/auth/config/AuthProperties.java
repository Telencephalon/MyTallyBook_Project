package com.mytallybook.accountbook.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        String bootstrapKey,
        String tokenPepper,
        Duration sessionTtl,
        int tokenBytes
) {

    private static final int MIN_BOOTSTRAP_KEY_LENGTH = 20;
    private static final int MAX_BOOTSTRAP_KEY_LENGTH = 256;
    private static final int MIN_TOKEN_PEPPER_LENGTH = 32;
    private static final int REQUIRED_TOKEN_BYTES = 32;

    public AuthProperties {
        bootstrapKey = bootstrapKey == null ? "" : bootstrapKey;
        if (!bootstrapKey.isEmpty()
                && (bootstrapKey.length() < MIN_BOOTSTRAP_KEY_LENGTH
                || bootstrapKey.length() > MAX_BOOTSTRAP_KEY_LENGTH)) {
            throw new IllegalArgumentException("bootstrapKey must contain 20 to 256 characters");
        }
        if (tokenPepper == null
                || tokenPepper.isBlank()
                || tokenPepper.length() < MIN_TOKEN_PEPPER_LENGTH) {
            throw new IllegalArgumentException("tokenPepper must contain at least 32 characters");
        }
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("sessionTtl must be positive");
        }
        if (tokenBytes != REQUIRED_TOKEN_BYTES) {
            throw new IllegalArgumentException("tokenBytes must be 32");
        }
    }
}
