package com.mytallybook.accountbook.auth.session;

import com.mytallybook.accountbook.auth.config.AuthProperties;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokenServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
    private static final String PEPPER = "0123456789abcdef0123456789abcdef";

    @Test
    void issuesThirtyTwoRandomBytesAsBase64UrlAndExpiresAfterThirtyDays() {
        SessionTokenService service = service(PEPPER, (byte) 0x5a);

        IssuedSessionToken issued = service.issue();

        assertThat(issued.rawToken()).hasSize(43).doesNotContain("=");
        assertThat(Base64.getUrlDecoder().decode(issued.rawToken())).hasSize(32);
        assertThat(issued.tokenHash()).matches("[0-9a-f]{64}");
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(issued.tokenHash()).isEqualTo(service.hash(issued.rawToken()));
    }

    @Test
    void computesTheApprovedHmacSha256Digest() {
        SessionTokenService service = service(PEPPER, (byte) 0x00);

        assertThat(service.hash("test-token"))
                .isEqualTo("0fd870f64aacd80f11dee9249a0ac525a2f5aac80220577affe28b9bc8f2fd6e");
    }

    @Test
    void changesDigestWhenTokenOrPepperChanges() {
        String baseline = service(PEPPER, (byte) 0x00).hash("test-token");

        assertThat(service(PEPPER, (byte) 0x00).hash("other-token"))
                .isNotEqualTo(baseline);
        assertThat(service("abcdef0123456789abcdef0123456789", (byte) 0x00)
                .hash("test-token"))
                .isNotEqualTo(baseline);
    }

    private static SessionTokenService service(String pepper, byte randomByte) {
        AuthProperties properties = new AuthProperties(
                "",
                pepper,
                Duration.ofDays(30),
                32
        );
        SecureRandom secureRandom = new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                java.util.Arrays.fill(bytes, randomByte);
            }
        };
        return new SessionTokenService(
                properties,
                secureRandom,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
