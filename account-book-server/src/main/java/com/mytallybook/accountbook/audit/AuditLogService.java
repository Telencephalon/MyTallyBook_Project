package com.mytallybook.accountbook.audit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Service
public class AuditLogService {

    private static final String INSERT_SQL = """
            INSERT INTO audit_log (
                ledger_id,
                user_id,
                action,
                resource_type,
                resource_id,
                request_id,
                details_json
            ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON))
            """;

    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "code",
            "wechatcode",
            "sessionkey",
            "bootstrapkey",
            "appbootstrapkey",
            "appsecret",
            "secret",
            "token",
            "accesstoken",
            "refreshtoken",
            "password",
            "authorization",
            "apikey",
            "credential"
            , "invitetoken", "invitecode", "tokenhash"
    );

    private final Supplier<JdbcTemplate> jdbcTemplateSupplier;
    private final ObjectMapper objectMapper;

    @Autowired
    public AuditLogService(
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider,
            ObjectMapper objectMapper
    ) {
        this(jdbcTemplateProvider::getIfAvailable, objectMapper);
    }

    private AuditLogService(
            Supplier<JdbcTemplate> jdbcTemplateSupplier,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplateSupplier = jdbcTemplateSupplier;
        this.objectMapper = objectMapper;
    }

    AuditLogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this((Supplier<JdbcTemplate>) () -> jdbcTemplate, objectMapper);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        validate(event);

        JdbcTemplate jdbcTemplate = jdbcTemplateSupplier.get();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Audit persistence requires a configured datasource");
        }

        String detailsJson = serializeDetails(event.details());
        jdbcTemplate.update(
                INSERT_SQL,
                new Object[]{
                        event.ledgerId(),
                        event.userId(),
                        event.action(),
                        event.resourceType(),
                        event.resourceId(),
                        event.requestId(),
                        detailsJson
                }
        );
    }

    private String serializeDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sanitizeValue(details, new IdentityHashMap<>()));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize audit details", exception);
        }
    }

    private Map<String, Object> sanitizeMap(
            Map<?, ?> source,
            IdentityHashMap<Object, Object> visited
    ) {
        Object existing = visited.get(source);
        if (existing instanceof Map<?, ?> existingMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typedMap = (Map<String, Object>) existingMap;
            return typedMap;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        visited.put(source, sanitized);
        source.forEach((key, value) -> {
            String keyText = String.valueOf(key);
            sanitized.put(
                    keyText,
                    isSensitiveKey(keyText) ? REDACTED : sanitizeValue(value, visited)
            );
        });
        return sanitized;
    }

    private Object sanitizeValue(Object value, IdentityHashMap<Object, Object> visited) {
        if (value == null || isSafeScalar(value)) {
            return value;
        }
        Object existing = visited.get(value);
        if (existing != null) {
            return existing;
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map, visited);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            visited.put(value, sanitized);
            iterable.forEach(item -> sanitized.add(sanitizeValue(item, visited)));
            return sanitized;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            visited.put(value, sanitized);
            for (int index = 0; index < Array.getLength(value); index++) {
                sanitized.add(sanitizeValue(Array.get(value, index), visited));
            }
            return sanitized;
        }
        return sanitizeBean(value, visited);
    }

    private Map<String, Object> sanitizeBean(
            Object source,
            IdentityHashMap<Object, Object> visited
    ) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        visited.put(source, sanitized);
        if (source.getClass().isRecord()) {
            for (java.lang.reflect.RecordComponent component : source.getClass().getRecordComponents()) {
                putSanitizedProperty(
                        sanitized,
                        component.getName(),
                        invoke(component.getAccessor(), source),
                        visited
                );
            }
            return sanitized;
        }
        addBeanProperties(source, sanitized, visited);
        addFields(source, sanitized, visited);
        return sanitized;
    }

    private void addBeanProperties(
            Object source,
            Map<String, Object> sanitized,
            IdentityHashMap<Object, Object> visited
    ) {
        try {
            for (PropertyDescriptor property : Introspector.getBeanInfo(source.getClass()).getPropertyDescriptors()) {
                Method getter = property.getReadMethod();
                if (!"class".equals(property.getName()) && getter != null && getter.getParameterCount() == 0) {
                    putSanitizedProperty(
                            sanitized,
                            property.getName(),
                            invoke(getter, source),
                            visited
                    );
                }
            }
        } catch (IntrospectionException exception) {
            throw new IllegalStateException("Unable to inspect audit detail object", exception);
        }
    }

    private void addFields(
            Object source,
            Map<String, Object> sanitized,
            IdentityHashMap<Object, Object> visited
    ) {
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                try {
                    if (field.trySetAccessible()) {
                        putSanitizedProperty(sanitized, field.getName(), field.get(source), visited);
                    }
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Unable to inspect audit detail object", exception);
                }
            }
        }
    }

    private void putSanitizedProperty(
            Map<String, Object> target,
            String name,
            Object value,
            IdentityHashMap<Object, Object> visited
    ) {
        target.put(name, isSensitiveKey(name) ? REDACTED : sanitizeValue(value, visited));
    }

    private Object invoke(Method method, Object source) {
        try {
            if (!method.canAccess(source)) {
                method.trySetAccessible();
            }
            return method.invoke(source);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Unable to inspect audit detail object", exception);
        }
    }

    private boolean isSafeScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return SENSITIVE_KEYS.contains(normalized)
                || normalized.endsWith("password")
                || normalized.endsWith("token")
                || normalized.endsWith("secret");
    }

    private void validate(AuditEvent event) {
        requireLength(event.action(), "action", 50);
        requireLength(event.resourceType(), "resourceType", 30);
        requireLength(event.requestId(), "requestId", 64);
        if (event.userId() == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
    }

    private void requireLength(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and " + maxLength + " characters"
            );
        }
    }

    public record AuditEvent(
            Long ledgerId,
            Long userId,
            String action,
            String resourceType,
            Long resourceId,
            String requestId,
            Map<String, ?> details
    ) {
    }
}
