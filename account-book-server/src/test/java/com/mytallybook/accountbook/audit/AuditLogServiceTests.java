package com.mytallybook.accountbook.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTests {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ObjectMapper objectMapper;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(jdbcTemplate, objectMapper);
    }

    @Test
    void appendsAnAuditRowAndRedactsSensitiveDetailValues() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"safe\":true}");

        auditLogService.append(new AuditLogService.AuditEvent(
                1L,
                101L,
                "ENTRY_CREATE",
                "BOOK_ENTRY",
                9001L,
                "client-request-123",
                Map.of(
                        "amount", "12.50",
                        "token", "raw-token-value",
                        "Authorization", "Bearer raw-token-value",
                        "wechat", Map.of("session_key", "raw-session-key"),
                        "items", List.of(Map.of("password", "raw-password"))
                )
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(objectMapper).writeValueAsString(detailsCaptor.capture());
        Map<String, Object> sanitized = detailsCaptor.getValue();

        assertThat(sanitized.get("amount")).isEqualTo("12.50");
        assertThat(sanitized.get("token")).isEqualTo("[REDACTED]");
        assertThat(sanitized.get("Authorization")).isEqualTo("[REDACTED]");
        @SuppressWarnings("unchecked")
        Map<String, Object> wechat = (Map<String, Object>) sanitized.get("wechat");
        assertThat(wechat)
                .containsEntry("session_key", "[REDACTED]");

        @SuppressWarnings("unchecked")
        Map<String, Object> firstItem = (Map<String, Object>)
                ((List<?>) sanitized.get("items")).getFirst();
        assertThat(firstItem)
                .containsEntry("password", "[REDACTED]");

        verify(jdbcTemplate).update(
                anyString(),
                any(Object[].class)
        );
    }
    @Test
    void invitationSecretsAreRecursivelyRedactedFromSerializedAudit() {
        var service = new AuditLogService(jdbcTemplate, new ObjectMapper());
        service.append(new AuditLogService.AuditEvent(1L, 1L, "INVITE_CREATE", "LEDGER_INVITE",
                51L, "dummy-request", Map.of("inviteToken", "dummy-raw-invite",
                "nested", List.of(Map.of("invite_code", "dummy-invite-code",
                        "tokenHash", "dummy-token-hash", "memberId", 12)))));
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat((String) args.getValue()[6]).contains("[REDACTED]", "memberId", "12")
                .doesNotContain("dummy-raw-invite", "dummy-invite-code", "dummy-token-hash");
    }

    @Test
    void serializedAuditDetailsDoNotLeakSensitiveValuesNestedInRecordsOrPojos()
            throws Exception {
        AuditLogService serviceWithRealSerializer = new AuditLogService(
                jdbcTemplate,
                new ObjectMapper()
        );
        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);

        serviceWithRealSerializer.append(new AuditLogService.AuditEvent(
                1L,
                101L,
                "ENTRY_CREATE",
                "BOOK_ENTRY",
                9001L,
                "client-request-456",
                Map.of(
                        "token", "nested-token-value",
                        "password", "nested-password-value",
                        "payload", List.of(
                                new BootstrapRecord("record-bootstrap-value", "record-wechat-code"),
                                new CredentialEnvelope(
                                        "pojo-app-bootstrap-value",
                                        new String[]{"array-session-key"}
                                )
                        )
                )
        ));

        verify(jdbcTemplate).update(anyString(), argumentsCaptor.capture());
        String serializedDetails = (String) argumentsCaptor.getValue()[6];

        assertThat(serializedDetails)
                .contains("[REDACTED]")
                .doesNotContain(
                        "record-bootstrap-value",
                        "record-wechat-code",
                        "pojo-app-bootstrap-value",
                        "array-session-key",
                        "nested-token-value",
                        "nested-password-value"
                );
    }

    @Test
    void serializedAuditDetailsRedactBootstrapKeyVariantsAndRetainNestedSafeFields()
            throws Exception {
        AuditLogService serviceWithRealSerializer = new AuditLogService(
                jdbcTemplate,
                new ObjectMapper()
        );
        ArgumentCaptor<Object[]> argumentsCaptor = ArgumentCaptor.forClass(Object[].class);

        serviceWithRealSerializer.append(new AuditLogService.AuditEvent(
                1L,
                101L,
                "ENTRY_CREATE",
                "BOOK_ENTRY",
                9001L,
                "client-request-789",
                Map.of("payload", new VariantEnvelope(
                        Map.of(
                                "Bootstrap_Key", "uppercase-underscore-bootstrap-value",
                                "APP-bootstrap-key", "uppercase-hyphen-app-bootstrap-value"
                        ),
                        "visible-safe-label"
                ))
        ));

        verify(jdbcTemplate).update(anyString(), argumentsCaptor.capture());
        String serializedDetails = (String) argumentsCaptor.getValue()[6];

        assertThat(serializedDetails)
                .contains("[REDACTED]")
                .contains("\"label\":\"visible-safe-label\"")
                .doesNotContain(
                        "uppercase-underscore-bootstrap-value",
                        "uppercase-hyphen-app-bootstrap-value"
                );
    }

    private record BootstrapRecord(String bootstrapKey, String wechatCode) {
    }

    private static final class CredentialEnvelope {

        private final String appBootstrapKey;
        private final String[] sessionKey;

        private CredentialEnvelope(String appBootstrapKey, String[] sessionKey) {
            this.appBootstrapKey = appBootstrapKey;
            this.sessionKey = sessionKey;
        }

        public String getAppBootstrapKey() {
            return appBootstrapKey;
        }

        public String[] getSessionKey() {
            return sessionKey;
        }
    }

    private static final class VariantEnvelope {

        private final Map<String, String> credentials;
        private final String label;

        private VariantEnvelope(Map<String, String> credentials, String label) {
            this.credentials = credentials;
            this.label = label;
        }

        public Map<String, String> getCredentials() {
            return credentials;
        }

        public String getLabel() {
            return label;
        }
    }
}
