package com.mytallybook.accountbook.auth.session;

import com.mytallybook.accountbook.auth.config.AuthProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class SessionTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final AuthProperties properties;
    private final SecureRandom secureRandom;
    private final Clock clock;

    public SessionTokenService(
            AuthProperties properties,
            SecureRandom secureRandom,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public IssuedSessionToken issue() {
        byte[] randomBytes = new byte[properties.tokenBytes()];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return new IssuedSessionToken(
                rawToken,
                hash(rawToken),
                clock.instant().plus(properties.sessionTtl())
        );
    }

    public String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("rawToken must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.tokenPepper().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            ));
            byte[] digest = mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to compute session token digest", exception);
        }
    }
}
