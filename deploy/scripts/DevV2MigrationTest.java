import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DevV2MigrationTest {
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";
    // Complete user-provided HEX(check_clause) fixtures from MySQL 8.0.46, 2026-09-05.
    private static final String OBSERVED_INVITE_STATUS = new String(HexFormat.of().parseHex(
            "28607374617475736020696E20285F757466386D62345C274143544956455C272C5F757466386D6234"
                    + "5C27555345445C272C5F757466386D62345C275245564F4B45445C272C5F757466386D62345C27"
                    + "455850495245445C272929"
    ), StandardCharsets.UTF_8);
    private static final String OBSERVED_INVITE_USE_STATE = new String(HexFormat.of().parseHex(
            "2828286073746174757360203D205F757466386D62345C27555345445C272920616E642028607573"
                    + "65645F627960206973206E6F74206E756C6C2920616E64202860757365645F617460206973206E"
                    + "6F74206E756C6C2929206F722028286073746174757360203C3E205F757466386D62345C275553"
                    + "45445C272920616E64202860757365645F627960206973206E756C6C2920616E64202860757365"
                    + "645F617460206973206E756C6C292929"
    ), StandardCharsets.UTF_8);
    private static final String OBSERVED_LEDGER_SINGLETON = new String(HexFormat.of().parseHex(
            "286073696E676C65746F6E5F6B657960203D203129"
    ), StandardCharsets.UTF_8);
    private static int passed;
    private static int failed;

    private DevV2MigrationTest() {
    }

    public static void main(String[] args) throws Exception {
        test("authorization must match the fixed database", () -> {
            invoke("validateAuthorization", new Class<?>[]{String.class}, "account_book_dev");
            expectFailure(() -> invoke(
                    "validateAuthorization",
                    new Class<?>[]{String.class},
                    "another_database"
            ));
            expectFailure(() -> invoke(
                    "validateAuthorization",
                    new Class<?>[]{String.class},
                    (Object) null
            ));
        });

        test("migration directory contains exactly the approved V1 and V2 scripts", () -> {
            Path directory = Files.createTempDirectory("dev-v2-migrations-");
            try {
                Files.writeString(directory.resolve("V1__init_schema.sql"), "SELECT 1;");
                Files.writeString(directory.resolve("V2__align_approved_design.sql"), "SELECT 2;");
                invoke(
                        "validateMigrationDirectory",
                        new Class<?>[]{String.class},
                        directory.toString()
                );

                Files.writeString(directory.resolve("unexpected.sql"), "SELECT 3;");
                expectFailure(() -> invoke(
                        "validateMigrationDirectory",
                        new Class<?>[]{String.class},
                        directory.toString()
                ));
            } finally {
                try (var files = Files.list(directory)) {
                    for (Path file : files.toList()) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(directory);
            }
        });

        test("stdin accepts exactly four strictly formatted lines", () -> {
            List<String> valid = validInput();
            invoke("parseInput", new Class<?>[]{List.class}, valid);

            expectFailure(() -> invoke(
                    "parseInput",
                    new Class<?>[]{List.class},
                    List.of("not base64", UUID, "-1", "2")
            ));
            expectFailure(() -> invoke(
                    "parseInput",
                    new Class<?>[]{List.class},
                    List.of(Base64.getEncoder().encodeToString(new byte[]{(byte) 0xff}), UUID, "-1", "2")
            ));
            expectFailure(() -> invoke(
                    "parseInput",
                    new Class<?>[]{List.class},
                    List.of(valid.get(0), "not-a-uuid", "-1", "2")
            ));
            expectFailure(() -> invoke(
                    "parseInput",
                    new Class<?>[]{List.class},
                    List.of(valid.get(0), UUID, "2147483648", "2")
            ));
            expectFailure(() -> invoke(
                    "parseInput",
                    new Class<?>[]{List.class},
                    List.of(valid.get(0), UUID, "+1", "2")
            ));
            List<String> extra = new ArrayList<>(valid);
            extra.add("unexpected");
            expectFailure(() -> invoke("parseInput", new Class<?>[]{List.class}, extra));
        });

        test("identity gate rejects the wrong database user version or server UUID", () -> {
            verifyIdentity("account_book_dev", "account_book_dev_app@127.0.0.1", "8.0.43", UUID, UUID);
            expectFailure(() -> verifyIdentity(
                    "another_database", "account_book_dev_app@127.0.0.1", "8.0.43", UUID, UUID
            ));
            expectFailure(() -> verifyIdentity(
                    "account_book_dev", "root@localhost", "8.0.43", UUID, UUID
            ));
            expectFailure(() -> verifyIdentity(
                    "account_book_dev", "account_book_dev_app@127.0.0.1", "8.4.0", UUID, UUID
            ));
            expectFailure(() -> verifyIdentity(
                    "account_book_dev", "account_book_dev_app@127.0.0.1", "8.0.43",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", UUID
            ));
        });

        test("history gate requires exact successful V1 before and V1 V2 after", () -> {
            Object v1 = history("1", "V1__init_schema.sql", -101, true);
            Object v2 = history("2", "V2__align_approved_design.sql", 202, true);
            invoke(
                    "verifyHistory", new Class<?>[]{List.class, boolean.class, int.class, int.class},
                    List.of(v1), false, -101, 202
            );
            invoke(
                    "verifyHistory", new Class<?>[]{List.class, boolean.class, int.class, int.class},
                    List.of(v1, v2), true, -101, 202
            );
            expectFailure(() -> invoke(
                    "verifyHistory", new Class<?>[]{List.class, boolean.class, int.class, int.class},
                    List.of(history("1", "renamed.sql", -101, true)), false, -101, 202
            ));
            expectFailure(() -> invoke(
                    "verifyHistory", new Class<?>[]{List.class, boolean.class, int.class, int.class},
                    List.of(history("1", "V1__init_schema.sql", -100, true)), false, -101, 202
            ));
            expectFailure(() -> invoke(
                    "verifyHistory", new Class<?>[]{List.class, boolean.class, int.class, int.class},
                    List.of(v1, v2), false, -101, 202
            ));
        });

        test("pre-migration snapshot rejects extra objects and changed row counts", () -> {
            Map<String, String> objects = expectedObjects();
            Map<String, Long> rows = expectedRows();
            invoke(
                    "verifyPreMigrationSnapshot",
                    new Class<?>[]{Map.class, Map.class},
                    objects,
                    rows
            );

            Map<String, String> extraObject = new LinkedHashMap<>(objects);
            extraObject.put("unexpected_view", "VIEW");
            expectFailure(() -> invoke(
                    "verifyPreMigrationSnapshot",
                    new Class<?>[]{Map.class, Map.class},
                    extraObject,
                    rows
            ));

            Map<String, Long> changedRows = new LinkedHashMap<>(rows);
            changedRows.put("app_user", 1L);
            expectFailure(() -> invoke(
                    "verifyPreMigrationSnapshot",
                    new Class<?>[]{Map.class, Map.class},
                    objects,
                    changedRows
            ));
        });

        test("index verification distinguishes unique and ordinary indexes", () -> {
            invoke(
                    "verifyIndexShape",
                    new Class<?>[]{List.class, List.class, boolean.class},
                    List.of(indexColumn("singleton_key", false)),
                    List.of("singleton_key"),
                    true
            );
            invoke(
                    "verifyIndexShape",
                    new Class<?>[]{List.class, List.class, boolean.class},
                    List.of(
                            indexColumn("ledger_id", true),
                            indexColumn("created_by", true),
                            indexColumn("entry_date", true)
                    ),
                    List.of("ledger_id", "created_by", "entry_date"),
                    false
            );
            expectFailure(() -> invoke(
                    "verifyIndexShape",
                    new Class<?>[]{List.class, List.class, boolean.class},
                    List.of(indexColumn("singleton_key", true)),
                    List.of("singleton_key"),
                    true
            ));
        });

        test("failure output exposes SQL diagnostics but never exception messages", () -> {
            String fields = (String) invoke(
                    "failureFields",
                    new Class<?>[]{Throwable.class},
                    new SQLException("password=do-not-print", "42000", 1045)
            );
            assertEquals(
                    "\"exception\":\"java.sql.SQLException\",\"sql_state\":\"42000\",\"error_code\":1045",
                    fields
            );
            if (fields.contains("do-not-print")) {
                throw new AssertionError("exception message leaked");
            }
        });

        test("manual Flyway validation ignores only future and pending migrations", () -> {
            Flyway flyway = (Flyway) invoke(
                    "createFlyway",
                    new Class<?>[]{Path.class, String.class},
                    Path.of(".").toAbsolutePath().normalize(),
                    "offline-only"
            );
            Set<String> patterns = Arrays.stream(
                            flyway.getConfiguration().getIgnoreMigrationPatterns()
                    )
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("*:future", "*:pending"), patterns);
            assertEquals("2", flyway.getConfiguration().getTarget().toString());
            assertEquals("account_book_dev", flyway.getConfiguration().getDefaultSchema());
            assertEquals(List.of("account_book_dev"), Arrays.asList(
                    flyway.getConfiguration().getSchemas()
            ));
            assertEquals(false, flyway.getConfiguration().isCreateSchemas());
            assertEquals(true, flyway.getConfiguration().isCleanDisabled());
            assertEquals(false, flyway.getConfiguration().isBaselineOnMigrate());
            assertEquals(true, flyway.getConfiguration().isValidateOnMigrate());
        });

        test("pending gate accepts exactly the trusted V2 migration", () -> {
            MigrationInfo trusted = migration("2", "V2__align_approved_design.sql", 202);
            invoke(
                    "verifyPendingMigration",
                    new Class<?>[]{MigrationInfo[].class, int.class},
                    (Object) new MigrationInfo[]{trusted},
                    202
            );
            expectIllegalState(() -> invoke(
                    "verifyPendingMigration",
                    new Class<?>[]{MigrationInfo[].class, int.class},
                    (Object) new MigrationInfo[]{migration("2", "V2__align_approved_design.sql", 203)},
                    202
            ));
            expectIllegalState(() -> invoke(
                    "verifyPendingMigration",
                    new Class<?>[]{MigrationInfo[].class, int.class},
                    (Object) new MigrationInfo[]{migration("2", "V2__different.sql", 202)},
                    202
            ));
            expectIllegalState(() -> invoke(
                    "verifyPendingMigration",
                    new Class<?>[]{MigrationInfo[].class, int.class},
                    (Object) new MigrationInfo[]{trusted, migration("3", "V3__extra.sql", 303)},
                    202
            ));
        });

        test("approved CHECK verifier rejects non-equivalent expressions with the same tokens", () -> {
            verifyApprovedCheck("ck_ledger_singleton", "YES", "(`singleton_key` = 1)");
            verifyApprovedCheck(
                    "ck_invite_status",
                    "YES",
                    "(`status` in (_utf8mb4'ACTIVE',_utf8mb4'USED',_utf8mb4'REVOKED',_utf8mb4'EXPIRED'))"
            );
            verifyApprovedCheck(
                    "ck_invite_use_state",
                    "YES",
                    "(((`status` = _utf8mb4'USED') and (`used_by` is not null)"
                            + " and (`used_at` is not null)) or ((`status` <> _utf8mb4'USED')"
                            + " and (`used_by` is null) and (`used_at` is null)))"
            );
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_status",
                    "YES",
                    "status not in ('ACTIVE','USED','REVOKED','EXPIRED')"
            ));
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_use_state",
                    "YES",
                    "((status = 'USED' and used_by is null and used_at is null) or "
                            + "(status <> 'USED' and used_by is not null and used_at is not null))"
            ));
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_ledger_singleton", "NO", "singleton_key = 1"
            ));
        });

        test("observed HEX invite status CHECK accepts literal backslash quote metadata", () -> {
            assertEquals(
                    "(`status` in (_utf8mb4\\'ACTIVE\\',_utf8mb4\\'USED\\',"
                            + "_utf8mb4\\'REVOKED\\',_utf8mb4\\'EXPIRED\\'))",
                    OBSERVED_INVITE_STATUS
            );
            verifyApprovedCheck("ck_invite_status", "YES", OBSERVED_INVITE_STATUS);
        });

        test("observed HEX invite use state CHECK accepts literal backslash quote metadata", () -> {
            assertEquals(
                    "(((`status` = _utf8mb4\\'USED\\') and (`used_by` is not null)"
                            + " and (`used_at` is not null)) or ((`status` <> _utf8mb4\\'USED\\')"
                            + " and (`used_by` is null) and (`used_at` is null)))",
                    OBSERVED_INVITE_USE_STATE
            );
            verifyApprovedCheck("ck_invite_use_state", "YES", OBSERVED_INVITE_USE_STATE);
        });

        test("observed HEX singleton CHECK remains accepted without escaped literals", () -> {
            assertEquals("(`singleton_key` = 1)", OBSERVED_LEDGER_SINGLETON);
            verifyApprovedCheck("ck_ledger_singleton", "YES", OBSERVED_LEDGER_SINGLETON);
        });

        test("escaped CHECK metadata still rejects changed status values", () -> {
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_status", "YES", OBSERVED_INVITE_STATUS.replace("ACTIVE", "PAUSED")
            ));
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_use_state", "YES", OBSERVED_INVITE_USE_STATE.replace("USED", "ACTIVE")
            ));
        });

        test("escaped CHECK metadata still rejects changed operators", () -> {
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_status", "YES", OBSERVED_INVITE_STATUS.replace(" in ", " not in ")
            ));
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_use_state", "YES", OBSERVED_INVITE_USE_STATE.replace("<>", "=")
            ));
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_use_state", "YES", OBSERVED_INVITE_USE_STATE.replace(" and ", " or ")
            ));
        });

        test("escaped CHECK metadata still rejects swapped NULL polarity", () -> {
            expectIllegalState(() -> verifyApprovedCheck(
                    "ck_invite_use_state",
                    "YES",
                    "(((`status` = _utf8mb4\\'USED\\') and (`used_by` is null)"
                            + " and (`used_at` is null)) or ((`status` <> _utf8mb4\\'USED\\')"
                            + " and (`used_by` is not null) and (`used_at` is not null)))"
            ));
        });

        test("observed CHECK metadata still rejects appended tautologies", () -> {
            for (Map.Entry<String, String> fixture : Map.of(
                    "ck_invite_status", OBSERVED_INVITE_STATUS,
                    "ck_invite_use_state", OBSERVED_INVITE_USE_STATE,
                    "ck_ledger_singleton", OBSERVED_LEDGER_SINGLETON
            ).entrySet()) {
                expectIllegalState(() -> verifyApprovedCheck(
                        fixture.getKey(), "YES", fixture.getValue() + " or 1=1"
                ));
            }
        });

        test("escaped CHECK metadata rejects malformed or extra escaping", () -> {
            for (String malformed : List.of(
                    OBSERVED_INVITE_STATUS.replace("\\'", "\\\\'"),
                    OBSERVED_INVITE_STATUS.replace("ACTIVE\\'", "ACTIVE'"),
                    OBSERVED_INVITE_STATUS.replace("\\'ACTIVE", "'ACTIVE"),
                    OBSERVED_INVITE_STATUS.replace("ACTIVE", "AC\\TIVE"),
                    OBSERVED_INVITE_STATUS.replace("_utf8mb4", ""),
                    OBSERVED_INVITE_STATUS.replace("_utf8mb4", "_utf8")
            )) {
                expectIllegalState(() -> verifyApprovedCheck("ck_invite_status", "YES", malformed));
            }
        });

        test("observed CHECK metadata still requires an approved name and YES enforcement", () -> {
            for (Map.Entry<String, String> fixture : Map.of(
                    "ck_invite_status", OBSERVED_INVITE_STATUS,
                    "ck_invite_use_state", OBSERVED_INVITE_USE_STATE,
                    "ck_ledger_singleton", OBSERVED_LEDGER_SINGLETON
            ).entrySet()) {
                expectIllegalState(() -> verifyApprovedCheck(
                        fixture.getKey(), "NO", fixture.getValue()
                ));
                expectIllegalState(() -> verifyApprovedCheck(
                        "unknown_constraint", "YES", fixture.getValue()
                ));
            }
        });

        if (failed != 0) {
            throw new AssertionError(failed + " tests failed; " + passed + " passed");
        }
        System.out.println("PASS " + passed + " offline tests");
    }

    private static List<String> validInput() {
        return List.of(
                Base64.getEncoder().encodeToString("secret".getBytes(StandardCharsets.UTF_8)),
                UUID,
                "-101",
                "202"
        );
    }

    private static Map<String, String> expectedObjects() {
        Map<String, String> objects = new LinkedHashMap<>();
        for (String table : List.of(
                "app_config", "app_user", "audit_log", "auth_session", "book_entry",
                "category", "flyway_schema_history", "fund_account", "ledger",
                "ledger_invite", "ledger_member"
        )) {
            objects.put(table, "BASE TABLE");
        }
        return objects;
    }

    private static Map<String, Long> expectedRows() {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : List.of(
                "app_config", "app_user", "audit_log", "auth_session", "book_entry",
                "category", "fund_account", "ledger", "ledger_invite", "ledger_member"
        )) {
            rows.put(table, table.equals("app_config") ? 1L : 0L);
        }
        return rows;
    }

    private static void verifyIdentity(
            String database,
            String user,
            String version,
            String serverUuid,
            String expectedUuid
    ) throws Exception {
        invoke(
                "verifyIdentity",
                new Class<?>[]{String.class, String.class, String.class, String.class, String.class},
                database, user, version, serverUuid, expectedUuid
        );
    }

    private static Object history(String version, String script, Integer checksum, boolean success)
            throws Exception {
        Class<?> rowClass = Class.forName("DevV2Migration$HistoryRow");
        Constructor<?> constructor = rowClass.getDeclaredConstructor(
                String.class, String.class, Integer.class, boolean.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(version, script, checksum, success);
    }

    private static Object indexColumn(String column, boolean nonUnique) throws Exception {
        Class<?> columnClass = Class.forName("DevV2Migration$IndexColumn");
        Constructor<?> constructor = columnClass.getDeclaredConstructor(String.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(column, nonUnique);
    }

    private static MigrationInfo migration(String version, String script, Integer checksum) {
        return (MigrationInfo) Proxy.newProxyInstance(
                DevV2MigrationTest.class.getClassLoader(),
                new Class<?>[]{MigrationInfo.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getVersion" -> MigrationVersion.fromVersion(version);
                    case "getScript" -> script;
                    case "getResolvedChecksum", "getChecksum" -> checksum;
                    case "compareTo" -> 0;
                    case "isVersioned", "isShouldExecute" -> true;
                    default -> null;
                }
        );
    }

    private static void verifyApprovedCheck(String constraint, String enforced, String clause)
            throws Exception {
        try {
            invoke(
                    "verifyApprovedCheck",
                    new Class<?>[]{String.class, String.class, String.class},
                    constraint,
                    enforced,
                    clause
            );
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("approved CHECK verifier is missing", missing);
        }
    }

    private static Object invoke(String methodName, Class<?>[] types, Object... arguments)
            throws Exception {
        Class<?> runner = Class.forName("DevV2Migration");
        Method method = runner.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static void expectFailure(ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static void expectIllegalState(ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("expected IllegalStateException");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void test(String name, ThrowingRunnable action) {
        try {
            action.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failed++;
            System.out.println("FAIL " + name + " [" + failure.getClass().getName() + "]");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
