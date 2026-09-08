package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.auth.config.AuthProperties;
import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class InviteTokenServiceTests {
    private final AuthProperties properties=new AuthProperties("","dummy-pepper-0123456789abcdef0123456789",Duration.ofDays(7),32);
    private final InviteTokenService tokens=new InviteTokenService(properties,new SecureRandom());
    @Test void generatesIndependent32ByteUnpaddedUrlSafeTokens() {
        Set<String> values=new HashSet<>();
        for(int index=0;index<100;index++) {
            String token=tokens.generate();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            values.add(token);
        }
        assertThat(values).hasSize(100);
    }
    @Test void hashesUseKnownIndependentDummyPepperVectorAndDifferentSessionDomain() {
        assertThat(tokens.hash("A".repeat(43)))
                .isEqualTo("bc2b5e1e63d98b0d41080d49411d177bbe40c9306fc27785076863b40e2dafda");
        assertThat(tokens.hash("A".repeat(43))).isNotEqualTo(
                new SessionTokenService(properties,new SecureRandom(),Clock.systemUTC()).hash("A".repeat(43)));
        assertThat(tokens.hash(" \n"+"A".repeat(43)+" ")).isEqualTo(tokens.hash("A".repeat(43)));
        assertThat(tokens.hash("a".repeat(43))).isNotEqualTo(tokens.hash("A".repeat(43)));
    }
    @Test void rejectsInvalidShapesWithoutEchoingSecrets() {
        for(String value:List.of("", "A".repeat(42),"A".repeat(44),"A".repeat(42)+"=","bad/sensitive"))
            assertThatThrownBy(()->tokens.hash(value)).isInstanceOf(BusinessException.class)
                    .hasMessage("请求参数不正确");
        assertThatThrownBy(()->tokens.hash(null)).isInstanceOf(BusinessException.class);
    }
}
