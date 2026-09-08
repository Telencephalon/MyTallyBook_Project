package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.auth.config.AuthProperties;
import com.mytallybook.accountbook.common.error.*;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
@Service
public class InviteTokenService {
    private final AuthProperties properties;
    private final SecureRandom random;
    public InviteTokenService(AuthProperties properties, SecureRandom random) {
        this.properties = properties; this.random = random;
    }
    public String generate() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String normalize(String value) {
        if (value == null || !value.trim().matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value.trim();
    }
    public String hash(String value) {
        String token = normalize(value);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.tokenPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("invite:v1:" + token).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to compute invitation digest", exception);
        }
    }
}
