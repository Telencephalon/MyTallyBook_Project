import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class DevV2Migration {
    private static final String ALLOWED_ENVIRONMENT = "ACCOUNT_BOOK_V2_ALLOWED";
    private static final String REQUIRED_AUTHORIZATION = "account_book_dev";
    private static final String SCHEMA = "account_book_dev";
    private static final String USER = "account_book_dev_app";
    private static final String REQUIRED_CURRENT_USER = "account_book_dev_app@127.0.0.1";
    private static final String JDBC_URL = "jdbc:mysql://127.0.0.1:13306/account_book_dev"
            + "?allowPublicKeyRetrieval=true&sslMode=DISABLED"
            + "&connectionTimeZone=Asia%2FShanghai&connectTimeout=10000&socketTimeout=60000";
    private static final String MIGRATION_LOCK = "mytallybook_dev_v2_upgrade";
    private static final String V1_SCRIPT = "V1__init_schema.sql";
    private static final String V2_SCRIPT = "V2__align_approved_design.sql";
    private static final Set<String> MIGRATION_FILES = Set.of(V1_SCRIPT, V2_SCRIPT);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    private static final Pattern SIGNED_CRC32_PATTERN = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final List<String> BUSINESS_TABLES = List.of(
            "app_config",
            "app_user",
            "audit_log",
            "auth_session",
            "book_entry",
            "category",
            "fund_account",
            "ledger",
            "ledger_invite",
            "ledger_member"
    );
    private static final Map<String, String> EXPECTED_OBJECTS = expectedObjects();
    private static final Map<String, Long> EXPECTED_PRE_MIGRATION_ROWS = expectedRows();
    private static final Map<String, String> APPROVED_CHECK_EXPRESSIONS = Map.of(
            "ck_ledger_singleton",
            "singleton_key=1",
            "ck_invite_status",
            "statusin('active','used','revoked','expired')",
            "ck_invite_use_state",
            "(status='used'andused_byisnotnullandused_atisnotnull)"
                    + "or(status<>'used'andused_byisnullandused_atisnull)"
    );

    private DevV2Migration() {
    }

    public static void main(String[] args) {
        int exitCode;
        try {
            execute(args);
            exitCode = 0;
        } catch (Throwable failure) {
            emitFailure(failure);
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void execute(String[] args) throws Exception {
        validateAuthorization(System.getenv(ALLOWED_ENVIRONMENT));
        if (args == null || args.length != 1) {
            throw new IllegalArgumentException("exactly one migration directory is required");
        }
        Path migrationDirectory = validateMigrationDirectory(args[0]);
        Input input = parseInput(readInputLines());

        try (Connection connection = DriverManager.getConnection(JDBC_URL, USER, input.password)) {
            Identity identity = readIdentity(connection);
            verifyIdentity(
                    identity.database,
                    identity.currentUser,
                    identity.version,
                    identity.serverUuid,
                    input.expectedServerUuid
            );
            emit("identity_verified", "");

            boolean lockAcquired = false;
            try {
                acquireLock(connection);
                lockAcquired = true;
                verifyNoOtherApplicationConnections(connection);

                List<HistoryRow> beforeHistory = readHistory(connection);
                verifyHistory(beforeHistory, false, input.v1Checksum, input.v2Checksum);
                Map<String, String> beforeObjects = readSchemaObjects(connection);
                Map<String, Long> beforeRows = readBusinessRowCounts(connection);
                verifyPreMigrationSnapshot(beforeObjects, beforeRows);

                Flyway flyway = createFlyway(migrationDirectory, input.password);
                flyway.validate();
                verifyPendingMigration(flyway.info().pending(), input.v2Checksum);

                emit("migration_started", "\"target\":\"2\"");
                MigrateResult result = flyway.migrate();
                if (!result.success || result.migrationsExecuted != 1) {
                    throw new IllegalStateException("unexpected Flyway migration result");
                }

                verifyHistory(
                        readHistory(connection),
                        true,
                        input.v1Checksum,
                        input.v2Checksum
                );
                verifySchemaObjectsAndRows(
                        readSchemaObjects(connection),
                        readBusinessRowCounts(connection),
                        beforeObjects,
                        beforeRows
                );
                verifyV2Schema(connection);
            } finally {
                if (lockAcquired) {
                    releaseLock(connection);
                }
            }
        }

        emit(
                "migration_complete",
                "\"versions\":[\"1\",\"2\"],"
                        + "\"migrations_executed\":1,"
                        + "\"v1_checksum\":" + input.v1Checksum + ","
                        + "\"v2_checksum\":" + input.v2Checksum
        );
    }

    static void validateAuthorization(String authorization) {
        if (!REQUIRED_AUTHORIZATION.equals(authorization)) {
            throw new IllegalArgumentException("migration authorization is missing");
        }
    }

    static Path validateMigrationDirectory(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("migration directory is missing");
        }
        Path directory = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("migration directory does not exist");
        }

        Map<String, Path> entries = new LinkedHashMap<>();
        try (var stream = Files.list(directory)) {
            for (Path entry : stream.toList()) {
                entries.put(entry.getFileName().toString(), entry);
            }
        }
        if (!entries.keySet().equals(MIGRATION_FILES)) {
            throw new IllegalArgumentException("migration directory contents are not approved");
        }
        for (String fileName : MIGRATION_FILES) {
            if (!Files.isRegularFile(entries.get(fileName), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("migration script is not a regular file");
            }
        }
        return directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    static Input parseInput(List<String> lines) {
        if (lines == null || lines.size() != 4 || lines.stream().anyMatch(line -> line == null)) {
            throw new IllegalArgumentException("stdin must contain exactly four lines");
        }

        byte[] passwordBytes;
        try {
            passwordBytes = Base64.getDecoder().decode(lines.get(0));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("password is not Base64", failure);
        }
        if (!Base64.getEncoder().encodeToString(passwordBytes).equals(lines.get(0))) {
            Arrays.fill(passwordBytes, (byte) 0);
            throw new IllegalArgumentException("password Base64 is not canonical");
        }

        String password;
        try {
            password = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(passwordBytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("password is not UTF-8", failure);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }

        String expectedServerUuid = parseUuid(lines.get(1));
        int v1Checksum = parseSignedCrc32(lines.get(2));
        int v2Checksum = parseSignedCrc32(lines.get(3));
        return new Input(password, expectedServerUuid, v1Checksum, v2Checksum);
    }

    private static List<String> readInputLines() throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, decoder))) {
            List<String> lines = new ArrayList<>(5);
            for (int index = 0; index < 5; index++) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                lines.add(line);
            }
            return lines;
        }
    }

    private static String parseUuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("server UUID is malformed");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("server UUID is malformed", failure);
        }
    }

    private static int parseSignedCrc32(String value) {
        if (value == null || !SIGNED_CRC32_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("checksum is not a signed CRC32");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("checksum is not a signed CRC32", failure);
        }
    }

    private static Identity readIdentity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT DATABASE(), CURRENT_USER(), VERSION(), @@server_uuid"
             )) {
            if (!rows.next()) {
                throw new SQLException("identity query returned no row");
            }
            Identity identity = new Identity(
                    rows.getString(1),
                    rows.getString(2),
                    rows.getString(3),
                    rows.getString(4)
            );
            if (rows.next()) {
                throw new SQLException("identity query returned multiple rows");
            }
            return identity;
        }
    }

    static void verifyIdentity(
            String database,
            String currentUser,
            String version,
            String serverUuid,
            String expectedServerUuid
    ) {
        if (!SCHEMA.equals(database)) {
            throw new IllegalArgumentException("database identity mismatch");
        }
        if (!REQUIRED_CURRENT_USER.equals(currentUser)) {
            throw new IllegalArgumentException("database user identity mismatch");
        }
        if (version == null || !version.matches("8\\.0\\.[0-9]+(?:[-+].*)?")) {
            throw new IllegalArgumentException("database version is not MySQL 8.0");
        }
        String actualUuid = parseUuid(serverUuid);
        String expectedUuid = parseUuid(expectedServerUuid);
        if (!actualUuid.equals(expectedUuid)) {
            throw new IllegalArgumentException("server UUID mismatch");
        }
    }

    private static void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, MIGRATION_LOCK);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull() || rows.next()) {
                    throw new IllegalStateException("migration lock was not acquired");
                }
            }
        }
    }

    private static void releaseLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, MIGRATION_LOCK);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull() || rows.next()) {
                    throw new IllegalStateException("migration lock was not released");
                }
            }
        }
    }

    private static void verifyNoOtherApplicationConnections(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.processlist
                WHERE USER = ?
                  AND ID <> CONNECTION_ID()
                """)) {
            statement.setString(1, USER);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != 0L || rows.next()) {
                    throw new IllegalStateException("another application connection exists");
                }
            }
        }
    }

    private static List<HistoryRow> readHistory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, script, checksum, success
                     FROM account_book_dev.flyway_schema_history
                     ORDER BY installed_rank
                     """)) {
            List<HistoryRow> history = new ArrayList<>();
            while (rows.next()) {
                int checksumValue = rows.getInt(3);
                Integer checksum = rows.wasNull() ? null : checksumValue;
                history.add(new HistoryRow(
                        rows.getString(1),
                        rows.getString(2),
                        checksum,
                        rows.getBoolean(4)
                ));
            }
            return history;
        }
    }

    static void verifyHistory(
            List<HistoryRow> rows,
            boolean afterMigration,
            int expectedV1Checksum,
            int expectedV2Checksum
    ) {
        int expectedSize = afterMigration ? 2 : 1;
        if (rows == null || rows.size() != expectedSize) {
            throw new IllegalArgumentException("Flyway history size mismatch");
        }
        verifyHistoryRow(rows.get(0), "1", V1_SCRIPT, expectedV1Checksum);
        if (afterMigration) {
            verifyHistoryRow(rows.get(1), "2", V2_SCRIPT, expectedV2Checksum);
        }
    }

    private static void verifyHistoryRow(
            HistoryRow row,
            String expectedVersion,
            String expectedScript,
            int expectedChecksum
    ) {
        if (row == null
                || !expectedVersion.equals(row.version)
                || !expectedScript.equals(row.script)
                || row.checksum == null
                || row.checksum != expectedChecksum
                || !row.success) {
            throw new IllegalArgumentException("Flyway history row mismatch");
        }
    }

    private static Map<String, String> readSchemaObjects(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name, table_type
                FROM information_schema.tables
                WHERE table_schema = ?
                ORDER BY table_name
                """)) {
            statement.setString(1, SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, String> objects = new LinkedHashMap<>();
                while (rows.next()) {
                    if (objects.put(rows.getString(1), rows.getString(2)) != null) {
                        throw new IllegalStateException("duplicate schema object");
                    }
                }
                return objects;
            }
        }
    }

    private static Map<String, Long> readBusinessRowCounts(Connection connection) throws SQLException {
        Map<String, Long> rowCounts = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT COUNT(*) FROM `" + SCHEMA + "`.`" + table + "`"
                 )) {
                if (!rows.next()) {
                    throw new SQLException("row-count query returned no row");
                }
                long count = rows.getLong(1);
                if (rows.next()) {
                    throw new SQLException("row-count query returned multiple rows");
                }
                rowCounts.put(table, count);
            }
        }
        return rowCounts;
    }

    static void verifyPreMigrationSnapshot(
            Map<String, String> objects,
            Map<String, Long> rowCounts
    ) {
        if (!EXPECTED_OBJECTS.equals(objects)) {
            throw new IllegalArgumentException("schema object snapshot mismatch");
        }
        if (!EXPECTED_PRE_MIGRATION_ROWS.equals(rowCounts)) {
            throw new IllegalArgumentException("business row snapshot mismatch");
        }
    }

    private static void verifySchemaObjectsAndRows(
            Map<String, String> objects,
            Map<String, Long> rowCounts,
            Map<String, String> beforeObjects,
            Map<String, Long> beforeRows
    ) {
        if (!EXPECTED_OBJECTS.equals(objects) || !beforeObjects.equals(objects)) {
            throw new IllegalStateException("schema objects changed unexpectedly");
        }
        if (!beforeRows.equals(rowCounts)) {
            throw new IllegalStateException("business row counts changed unexpectedly");
        }
    }

    private static Flyway createFlyway(Path migrationDirectory, String password) {
        String location = "filesystem:" + migrationDirectory.toString().replace('\\', '/');
        return Flyway.configure()
                .dataSource(JDBC_URL, USER, password)
                .locations(location)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target("2")
                .createSchemas(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .ignoreMigrationPatterns("*:future", "*:pending")
                .failOnMissingLocations(true)
                .connectRetries(0)
                .lockRetryCount(0)
                .encoding(StandardCharsets.UTF_8)
                .load();
    }

    private static void verifyPendingMigration(MigrationInfo[] pending, int expectedV2Checksum) {
        if (pending == null || pending.length != 1) {
            throw new IllegalStateException("expected exactly one pending migration");
        }
        MigrationInfo migration = pending[0];
        Integer checksum = migration.getResolvedChecksum();
        if (checksum == null) {
            checksum = migration.getChecksum();
        }
        if (migration.getVersion() == null
                || !"2".equals(migration.getVersion().toString())
                || !V2_SCRIPT.equals(migration.getScript())
                || checksum == null
                || checksum != expectedV2Checksum) {
            throw new IllegalStateException("pending migration mismatch");
        }
    }

    private static void verifyV2Schema(Connection connection) throws SQLException {
        ColumnInfo singleton = requireColumn(connection, "ledger", "singleton_key");
        require(singleton.dataType.equals("tinyint"));
        require(singleton.nullable.equals("NO"));
        require("1".equals(singleton.defaultValue));
        List<IndexColumn> singletonIndex = indexColumns(
                connection, "ledger", "uk_ledger_singleton"
        );
        verifyIndexShape(singletonIndex, List.of("singleton_key"), true);
        requireCheck(connection, "ledger", "ck_ledger_singleton");

        ColumnInfo usedBy = requireColumn(connection, "ledger_invite", "used_by");
        require(usedBy.dataType.equals("bigint") && usedBy.nullable.equals("YES"));
        ColumnInfo usedAt = requireColumn(connection, "ledger_invite", "used_at");
        require(usedAt.dataType.equals("datetime") && usedAt.nullable.equals("YES"));
        require(column(connection, "ledger_invite", "max_uses") == null);
        require(column(connection, "ledger_invite", "used_count") == null);
        requireForeignKey(
                connection,
                "ledger_invite",
                "fk_invite_consumer",
                "used_by",
                "app_user",
                "id"
        );
        requireCheck(connection, "ledger_invite", "ck_invite_status");
        requireCheck(connection, "ledger_invite", "ck_invite_use_state");

        require(column(connection, "book_entry", "member_id") == null);
        require(indexColumns(connection, "book_entry", "idx_entry_member_date").isEmpty());
        require(!foreignKeyExists(connection, "book_entry", "fk_entry_member"));
        verifyIndexShape(
                indexColumns(connection, "book_entry", "idx_entry_creator_date"),
                List.of("ledger_id", "created_by", "entry_date"),
                false
        );

        ColumnInfo requestId = requireColumn(connection, "audit_log", "request_id");
        require(requestId.dataType.equals("varchar"));
        require(Long.valueOf(64L).equals(requestId.maximumLength));
        require("ascii".equals(requestId.characterSet));
        require("ascii_bin".equals(requestId.collation));
        require("NO".equals(requestId.nullable));
    }

    private static ColumnInfo requireColumn(
            Connection connection,
            String table,
            String columnName
    ) throws SQLException {
        ColumnInfo column = column(connection, table, columnName);
        if (column == null) {
            throw new IllegalStateException("required column is absent");
        }
        return column;
    }

    private static ColumnInfo column(Connection connection, String table, String columnName)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type, is_nullable, column_default, character_maximum_length,
                       character_set_name, collation_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name = ?
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, columnName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                long maximumLengthValue = rows.getLong(4);
                Long maximumLength = rows.wasNull() ? null : maximumLengthValue;
                ColumnInfo result = new ColumnInfo(
                        lower(rows.getString(1)),
                        rows.getString(2),
                        rows.getString(3),
                        maximumLength,
                        lower(rows.getString(5)),
                        lower(rows.getString(6))
                );
                if (rows.next()) {
                    throw new IllegalStateException("duplicate column metadata");
                }
                return result;
            }
        }
    }

    private static List<IndexColumn> indexColumns(
            Connection connection,
            String table,
            String index
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, non_unique
                FROM information_schema.statistics
                WHERE table_schema = ?
                  AND table_name = ?
                  AND index_name = ?
                ORDER BY seq_in_index
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, index);
            try (ResultSet rows = statement.executeQuery()) {
                List<IndexColumn> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new IndexColumn(rows.getString(1), rows.getInt(2) != 0));
                }
                return result;
            }
        }
    }

    static void verifyIndexShape(
            List<IndexColumn> actual,
            List<String> expectedColumns,
            boolean unique
    ) {
        if (actual == null || expectedColumns == null || actual.size() != expectedColumns.size()) {
            throw new IllegalArgumentException("index shape mismatch");
        }
        boolean expectedNonUnique = !unique;
        for (int index = 0; index < expectedColumns.size(); index++) {
            IndexColumn actualColumn = actual.get(index);
            if (actualColumn == null
                    || !expectedColumns.get(index).equals(actualColumn.column)
                    || actualColumn.nonUnique != expectedNonUnique) {
                throw new IllegalArgumentException("index shape mismatch");
            }
        }
    }

    private static void requireForeignKey(
            Connection connection,
            String table,
            String constraint,
            String column,
            String referencedTable,
            String referencedColumn
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name, referenced_table_name, referenced_column_name
                FROM information_schema.key_column_usage
                WHERE constraint_schema = ?
                  AND table_name = ?
                  AND constraint_name = ?
                  AND referenced_table_name IS NOT NULL
                ORDER BY ordinal_position
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, constraint);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
                require(column.equals(rows.getString(1)));
                require(referencedTable.equals(rows.getString(2)));
                require(referencedColumn.equals(rows.getString(3)));
                require(!rows.next());
            }
        }
    }

    private static boolean foreignKeyExists(
            Connection connection,
            String table,
            String constraint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = ?
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, constraint);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
                long count = rows.getLong(1);
                require(!rows.next());
                return count != 0;
            }
        }
    }

    private static void requireCheck(
            Connection connection,
            String table,
            String constraint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT constraints.enforced, checks.check_clause
                FROM information_schema.table_constraints constraints
                JOIN information_schema.check_constraints checks
                  ON checks.constraint_schema = constraints.constraint_schema
                 AND checks.constraint_name = constraints.constraint_name
                WHERE constraints.constraint_schema = ?
                  AND constraints.table_name = ?
                  AND constraints.constraint_name = ?
                  AND constraints.constraint_type = 'CHECK'
                """)) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, constraint);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
                verifyApprovedCheck(constraint, rows.getString(1), rows.getString(2));
                require(!rows.next());
            }
        }
    }

    static void verifyApprovedCheck(String constraint, String enforced, String clause) {
        String approved = APPROVED_CHECK_EXPRESSIONS.get(constraint);
        if (!"YES".equals(enforced)
                || approved == null
                || !approved.equals(normalizeCheckClause(clause))) {
            throw new IllegalStateException("post-migration CHECK verification failed");
        }
    }

    private static String normalizeCheckClause(String clause) {
        if (clause == null) {
            throw new IllegalStateException("check clause is absent");
        }
        String normalized = clause.toLowerCase(Locale.ROOT)
                // Observed MySQL metadata escapes both quotes of charset-introduced status literals.
                .replaceAll("_utf8mb4\\\\'(active|used|revoked|expired)\\\\'", "'$1'")
                .replace("`", "")
                .replaceAll("_[a-z0-9]+(?=')", "")
                .replaceAll("\\s+", "");
        normalized = stripOuterParentheses(normalized);
        String previous;
        do {
            previous = normalized;
            normalized = normalized.replaceAll(
                    "\\(([a-z_][a-z0-9_]*(?:=|<>)'[^']*'"
                            + "|[a-z_][a-z0-9_]*is(?:not)?null"
                            + "|[a-z_][a-z0-9_]*=-?[0-9]+)\\)",
                    "$1"
            );
            normalized = stripOuterParentheses(normalized);
        } while (!normalized.equals(previous));
        return normalized;
    }

    private static String stripOuterParentheses(String expression) {
        String result = expression;
        while (result.length() >= 2 && result.charAt(0) == '('
                && result.charAt(result.length() - 1) == ')') {
            int depth = 0;
            boolean surroundsWholeExpression = true;
            for (int index = 0; index < result.length(); index++) {
                char character = result.charAt(index);
                if (character == '(') {
                    depth++;
                } else if (character == ')') {
                    depth--;
                    if (depth == 0 && index != result.length() - 1) {
                        surroundsWholeExpression = false;
                        break;
                    }
                }
                if (depth < 0) {
                    throw new IllegalStateException("malformed CHECK expression");
                }
            }
            if (!surroundsWholeExpression || depth != 0) {
                break;
            }
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("post-migration schema verification failed");
        }
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> expectedObjects() {
        Map<String, String> objects = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            objects.put(table, "BASE TABLE");
        }
        objects.put("flyway_schema_history", "BASE TABLE");
        return Map.copyOf(objects);
    }

    private static Map<String, Long> expectedRows() {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            rows.put(table, table.equals("app_config") ? 1L : 0L);
        }
        return Map.copyOf(rows);
    }

    private static void emit(String event, String fields) {
        String separator = fields.isEmpty() ? "" : ",";
        System.out.println("V2_EVENT {\"event\":\"" + event + "\"" + separator + fields + "}");
        System.out.flush();
    }

    private static void emitFailure(Throwable failure) {
        emit("migration_failed", failureFields(failure));
    }

    static String failureFields(Throwable failure) {
        SQLException sqlFailure = findSqlException(failure);
        StringBuilder fields = new StringBuilder()
                .append("\"exception\":")
                .append(jsonString(failure.getClass().getName()));
        if (sqlFailure != null) {
            if (sqlFailure.getSQLState() != null) {
                fields.append(",\"sql_state\":").append(jsonString(sqlFailure.getSQLState()));
            }
            fields.append(",\"error_code\":").append(sqlFailure.getErrorCode());
        }
        return fields.toString();
    }

    private static SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlFailure) {
                return sqlFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    static final class Input {
        private final String password;
        private final String expectedServerUuid;
        private final int v1Checksum;
        private final int v2Checksum;

        private Input(String password, String expectedServerUuid, int v1Checksum, int v2Checksum) {
            this.password = password;
            this.expectedServerUuid = expectedServerUuid;
            this.v1Checksum = v1Checksum;
            this.v2Checksum = v2Checksum;
        }
    }

    record HistoryRow(String version, String script, Integer checksum, boolean success) {
    }

    private record Identity(String database, String currentUser, String version, String serverUuid) {
    }

    private record ColumnInfo(
            String dataType,
            String nullable,
            String defaultValue,
            Long maximumLength,
            String characterSet,
            String collation
    ) {
    }

    private record IndexColumn(String column, boolean nonUnique) {
    }
}
